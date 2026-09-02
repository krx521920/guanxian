package com.guanxian.platform.iam;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JWSAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Verifies that the configured IdP can currently provide a usable JSON Web Key Set. */
@Component("oidcJwkSet")
@ConditionalOnProperty(
        name = "guanxian.security.jwt.health-check-enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(
        name = "guanxian.security.mode",
        havingValue = "jwt",
        matchIfMissing = true)
final class OidcJwkSetHealthIndicator implements HealthIndicator {
    private static final int MAX_JWKS_BYTES = 1024 * 1024;

    private final URI jwkSetUri;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final HttpClient httpClient;
    private final Object cacheMonitor = new Object();
    private volatile CachedHealth cachedHealth;

    OidcJwkSetHealthIndicator(
            @Value("${guanxian.security.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${guanxian.security.jwt.health-check-timeout:2s}") Duration timeout,
            @Value("${guanxian.security.jwt.health-check-cache-ttl:10s}") Duration cacheTtl,
            Environment environment) {
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("OIDC health-check timeout must be between 1 ms and 10 s");
        }
        if (cacheTtl.compareTo(Duration.ofSeconds(1)) < 0
                || cacheTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("OIDC health-check cache TTL must be between 1 s and 5 min");
        }
        this.jwkSetUri = SecurityConfig.validatedEndpoint(
                "JWK set", jwkSetUri, environment.getActiveProfiles());
        this.timeout = timeout;
        this.cacheTtl = cacheTtl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Health health() {
        long now = System.nanoTime();
        CachedHealth current = cachedHealth;
        if (current != null && now < current.expiresAtNanos()) {
            return current.health();
        }
        synchronized (cacheMonitor) {
            now = System.nanoTime();
            current = cachedHealth;
            if (current != null && now < current.expiresAtNanos()) {
                return current.health();
            }
            Health probed = probe();
            cachedHealth = new CachedHealth(now + cacheTtl.toNanos(), probed);
            return probed;
        }
    }

    private Health probe() {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(jwkSetUri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var input = response.body()) {
                long declaredLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declaredLength > MAX_JWKS_BYTES) {
                    return Health.down().build();
                }
                body = readWithDeadline(input, deadlineNanos);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || body.length == 0 || body.length > MAX_JWKS_BYTES) {
                return Health.down().build();
            }
            JWKSet jwkSet = JWKSet.parse(new String(body, StandardCharsets.UTF_8));
            boolean usableSigningKey = jwkSet.getKeys().stream().anyMatch(key ->
                    key instanceof RSAKey
                            && key.getKeyID() != null
                            && !key.getKeyID().isBlank()
                            && (key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse()))
                            && (key.getAlgorithm() == null
                            || JWSAlgorithm.RS256.equals(key.getAlgorithm()))
                            && key.toPublicJWK() != null);
            return usableSigningKey
                    ? Health.up().build()
                    : Health.down().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down(exception).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }

    private byte[] readWithDeadline(InputStream input, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("OIDC health-check deadline exceeded before body read");
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> future = executor.submit(() -> input.readNBytes(MAX_JWKS_BYTES + 1));
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } finally {
            executor.shutdownNow();
        }
    }

    private record CachedHealth(long expiresAtNanos, Health health) {
    }
}
