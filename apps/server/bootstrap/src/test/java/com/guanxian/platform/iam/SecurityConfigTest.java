package com.guanxian.platform.iam;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.boot.actuate.health.Status;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTest {
    @Test
    void productionProfilesRejectDemoAuthentication() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("demo", new String[]{"prod"}));
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("demo", new String[]{"PRODUCTION"}));
        assertDoesNotThrow(() -> SecurityConfig.validateSecurityMode("jwt", new String[]{"prod"}));
    }

    @Test
    void unknownAuthenticationModeFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("basic", new String[]{"dev"}));
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("", new String[]{}));
    }

    @Test
    void jwtRolesAndPermissionsAreWhitelistedAndExpanded() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .claim("roles", List.of("association_admin", "UNTRUSTED_ROLE"))
                .claim("realm_access", Map.of("roles", List.of("ENTERPRISE_MEMBER")))
                .claim("permissions", List.of("POLICY_READ", "ROOT_ACCESS"))
                .build();

        List<String> authorities = SecurityConfig.authoritiesFor(jwt).stream()
                .map(authority -> authority.getAuthority())
                .toList();

        assertTrue(authorities.contains("ROLE_ASSOCIATION_ADMIN"));
        assertTrue(authorities.contains("ROLE_ENTERPRISE_MEMBER"));
        assertTrue(authorities.contains("ENTERPRISE_WRITE"));
        assertTrue(authorities.contains("DASHBOARD_ENTERPRISE_READ"));
        assertTrue(authorities.contains("POLICY_READ"));
        assertTrue(authorities.contains("NOTIFICATION_READ"));
        assertTrue(authorities.contains("NOTIFICATION_PUBLISH"));
        assertFalse(authorities.contains("ROLE_UNTRUSTED_ROLE"));
        assertFalse(authorities.contains("ROOT_ACCESS"));
    }

    @Test
    void enterpriseRoleCannotAcquireNotificationPublishPermission() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("enterprise-user")
                .claim("roles", List.of("ENTERPRISE_ADMIN"))
                .build();

        List<String> authorities = SecurityConfig.authoritiesFor(jwt).stream()
                .map(authority -> authority.getAuthority())
                .toList();

        assertTrue(authorities.contains("NOTIFICATION_READ"));
        assertFalse(authorities.contains("NOTIFICATION_PUBLISH"));
    }

    @Test
    void demoUserStoreExistsOnlyForExplicitDevelopmentMode() {
        SecurityConfig config = new SecurityConfig();
        InMemoryUserDetailsManager development =
                (InMemoryUserDetailsManager) config.userDetailsService(environmentWithProfiles("dev"));

        assertTrue(development.userExists("system-admin"));
        assertTrue(development.userExists("enterprise-member"));
        assertThrows(IllegalStateException.class,
                () -> config.userDetailsService(environmentWithProfiles("production")));
    }

    @Test
    void jwtEndpointsMustBeConfiguredAndUseHttpsInProduction() {
        SecurityConfig config = new SecurityConfig();
        Environment development = environmentWithProfiles("dev");
        Environment production = environmentWithProfiles("prod");

        assertDoesNotThrow(() -> config.jwtDecoder(
                "http://localhost:8088/realms/guanxian",
                "http://localhost:8088/realms/guanxian/certs",
                development));
        assertThrows(IllegalStateException.class,
                () -> config.jwtDecoder("", "https://identity.example.com/certs", development));
        assertThrows(IllegalStateException.class,
                () -> config.jwtDecoder(
                        "http://identity.example.com/realms/guanxian",
                        "https://identity.example.com/certs",
                        production));
    }

    @Test
    void jwtDecoderLoadsHttpJwksAndValidatesSignatureAndIssuer() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var keyPair = keyPairGenerator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("integration-key")
                .build();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        byte[] ecOnlyJwks = new JWKSet(new ECKeyGenerator(Curve.P_256)
                .keyID("ec-signing-key")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.ES256)
                .generate()
                .toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        AtomicInteger jwksRequests = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            jwksRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        server.createContext("/invalid-jwks", exchange -> {
            byte[] invalid = "{\"keys\":[{}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, invalid.length);
            try (var body = exchange.getResponseBody()) {
                body.write(invalid);
            }
        });
        server.createContext("/ec-jwks", exchange -> {
            exchange.sendResponseHeaders(200, ecOnlyJwks.length);
            try (var body = exchange.getResponseBody()) {
                body.write(ecOnlyJwks);
            }
        });
        server.createContext("/stalled-jwks", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("{\"keys\":[".getBytes(StandardCharsets.UTF_8));
                body.flush();
                Thread.sleep(3_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            String issuer = origin + "/realms/guanxian";
            var decoder = new SecurityConfig().jwtDecoder(
                    issuer, origin + "/jwks", environmentWithProfiles("test"));

            Jwt decoded = decoder.decode(signedToken(key, issuer));
            assertTrue("real-oidc-subject".equals(decoded.getSubject()));
            assertThrows(JwtValidationException.class,
                    () -> decoder.decode(signedToken(key, origin + "/wrong-issuer")));

            jwksRequests.set(0);
            var healthy = new OidcJwkSetHealthIndicator(
                    origin + "/jwks", Duration.ofSeconds(2), Duration.ofSeconds(10),
                    environmentWithProfiles("test"));
            var malformed = new OidcJwkSetHealthIndicator(
                    origin + "/invalid-jwks", Duration.ofSeconds(2), Duration.ofSeconds(10),
                    environmentWithProfiles("test"));
            var unsupportedAlgorithm = new OidcJwkSetHealthIndicator(
                    origin + "/ec-jwks", Duration.ofSeconds(2), Duration.ofSeconds(10),
                    environmentWithProfiles("test"));
            var stalled = new OidcJwkSetHealthIndicator(
                    origin + "/stalled-jwks", Duration.ofMillis(200), Duration.ofSeconds(1),
                    environmentWithProfiles("test"));
            assertTrue(Status.UP.equals(healthy.health().getStatus()));
            assertTrue(Status.UP.equals(healthy.health().getStatus()));
            assertEquals(1, jwksRequests.get(), "health checks within the TTL must share one probe");
            assertTrue(Status.DOWN.equals(malformed.health().getStatus()));
            assertTrue(Status.DOWN.equals(unsupportedAlgorithm.health().getStatus()));
            long started = System.nanoTime();
            assertTrue(Status.DOWN.equals(stalled.health().getStatus()));
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos(),
                    "a stalled JWKS body must respect the read deadline");
        } finally {
            server.stop(0);
        }
    }

    private static String signedToken(RSAKey key, String issuer) throws Exception {
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .subject("real-oidc-subject")
                        .issuer(issuer)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private static Environment environmentWithProfiles(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return environment;
    }
}
