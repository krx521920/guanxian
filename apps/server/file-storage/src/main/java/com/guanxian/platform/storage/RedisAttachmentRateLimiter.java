package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
@ConditionalOnProperty(name = "guanxian.storage.rate-limit.enabled", havingValue = "true")
final class RedisAttachmentRateLimiter implements AttachmentRateLimiter {
    private static final DefaultRedisScript<Long> FIXED_WINDOW = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final NamedParameterJdbcTemplate jdbc;
    private final int limit;

    RedisAttachmentRateLimiter(
            StringRedisTemplate redis,
            NamedParameterJdbcTemplate jdbc,
            StorageProperties properties) {
        if (properties.getRateLimitPerMinute() < 1 || properties.getRateLimitPerMinute() > 10_000) {
            throw new IllegalStateException("attachment rate limit must be between 1 and 10000 per minute");
        }
        this.redis = redis;
        this.jdbc = jdbc;
        this.limit = properties.getRateLimitPerMinute();
    }

    @Override
    public void check(ActorScope actor, String action) {
        String subjectHash = sha256(actor.subject());
        String route = "attachment:" + action;
        try {
            Long count = redis.execute(FIXED_WINDOW,
                    List.of("guanxian:rate:" + route + ":" + subjectHash), "60");
            if (count == null) {
                audit(subjectHash, route, "ERROR");
                throw new StorageUnavailableException("rate limiter returned no decision");
            }
            if (count > limit) {
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
