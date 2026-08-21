package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScope;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

@Component
@ConditionalOnProperty(name = "guanxian.storage.rate-limit.enabled", havingValue = "true")
final class RedisAttachmentRateLimiter implements AttachmentRateLimiter {
    private static final String FIXED_WINDOW = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private final JedisPooled redis;
    private final NamedParameterJdbcTemplate jdbc;
    private final int limit;

    RedisAttachmentRateLimiter(
            NamedParameterJdbcTemplate jdbc,
            StorageProperties properties,
            Environment environment) {
        if (properties.getRateLimitPerMinute() < 1 || properties.getRateLimitPerMinute() > 10_000) {
            throw new IllegalStateException(
                    "attachment rate limit must be between 1 and 10000 per minute");
        }
        URI redisUri = validatedRedisUri(properties.getRedisUrl(), environment.getActiveProfiles());
        this.redis = new JedisPooled(redisUri);
        this.jdbc = jdbc;
        this.limit = properties.getRateLimitPerMinute();
    }

    @Override
    public void check(ActorScope actor, String action) {
        String subjectHash = sha256(actor.subject());
        String route = "attachment:" + action;
        try {
            Object raw = redis.eval(FIXED_WINDOW,
                    List.of("guanxian:rate:" + route + ":" + subjectHash),
                    List.of("60"));
            if (!(raw instanceof Number number)) {
                audit(subjectHash, route, "ERROR");
                throw new StorageUnavailableException("rate limiter returned no decision");
            }
            if (number.longValue() > limit) {
                audit(subjectHash, route, "REJECTED");
                throw new ApiException("RATE_LIMIT_EXCEEDED",
                        "too many attachment operations; retry after the current minute",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            audit(subjectHash, route, "ALLOWED");
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            auditBestEffort(subjectHash, route, "ERROR");
            throw new StorageUnavailableException(
                    "attachment rate limiter is unavailable; write operation rejected", exception);
        }
    }

    @PreDestroy
    void close() {
        redis.close();
    }

    private void audit(String subjectHash, String route, String decision) {
        jdbc.update("""
                INSERT INTO rate_limit_audit (
                    subject_key_hash, route_key, decision, request_id)
                VALUES (:subjectHash, :route, :decision, :requestId)
                """, new MapSqlParameterSource()
                .addValue("subjectHash", subjectHash)
                .addValue("route", route)
                .addValue("decision", decision)
                .addValue("requestId", MDC.get("requestId")));
    }

    private void auditBestEffort(String subjectHash, String route, String decision) {
        try {
            audit(subjectHash, route, decision);
        } catch (RuntimeException ignored) {
            // Preserve the Redis failure as the externally visible fail-closed reason.
        }
    }

    private static URI validatedRedisUri(String raw, String[] profiles) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            boolean tls = "rediss".equalsIgnoreCase(uri.getScheme());
            boolean plain = "redis".equalsIgnoreCase(uri.getScheme());
            if ((!tls && !plain) || uri.getHost() == null || uri.getFragment() != null) {
                throw new IllegalStateException("Redis URL must be a redis:// or rediss:// endpoint");
            }
            boolean production = Arrays.stream(profiles).anyMatch(profile ->
                    "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
            if (production && !tls) {
                throw new IllegalStateException("Redis URL must use rediss:// in production");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Redis URL is invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
