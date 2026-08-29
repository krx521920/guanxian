package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseActorScopeResolverTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID PARTNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private DatabaseActorScopeResolver resolver;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:actor-scope-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE association (id UUID PRIMARY KEY, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE')");
        jdbc.execute("CREATE TABLE enterprise (id UUID PRIMARY KEY, association_id UUID NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE')");
        jdbc.execute("CREATE TABLE user_account (id UUID PRIMARY KEY, association_id UUID, enterprise_id UUID, external_subject VARCHAR(200), username VARCHAR(100), status VARCHAR(32))");
        jdbc.execute("CREATE TABLE association_relationship (source_association_id UUID, target_association_id UUID, status VARCHAR(32), allow_member_data BOOLEAN, expires_at TIMESTAMP WITH TIME ZONE, suspended_at TIMESTAMP WITH TIME ZONE, revoked_at TIMESTAMP WITH TIME ZONE)");
        resolver = new DatabaseActorScopeResolver(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void resolvesEnterpriseAndPartnerScopeOnlyFromTheBoundOidcSubject() {
        jdbc.update("INSERT INTO association(id) VALUES (?), (?)", ASSOCIATION, PARTNER);
        jdbc.update("INSERT INTO enterprise(id, association_id) VALUES (?, ?)", ENTERPRISE, ASSOCIATION);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account(id, association_id, enterprise_id, external_subject, username, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                user, ASSOCIATION, ENTERPRISE, "oidc-enterprise", "enterprise.user");
        jdbc.update("INSERT INTO association_relationship(source_association_id, target_association_id, status, allow_member_data) VALUES (?, ?, 'ACTIVE', TRUE)",
                ASSOCIATION, PARTNER);

        var actor = resolver.resolve(authentication("oidc-enterprise", "enterprise.user", "ENTERPRISE_ADMIN"));

        assertEquals(user, actor.userId());
        assertEquals(ASSOCIATION, actor.associationId());
        assertEquals(ENTERPRISE, actor.enterpriseId());
        assertEquals(java.util.Set.of(PARTNER), actor.partnerAssociationIds());
    }

    @Test
    void excludesExpiredSuspendedAndRevokedPartnerRelationships() {
        jdbc.update("INSERT INTO association(id) VALUES (?), (?)", ASSOCIATION, PARTNER);
        UUID expiredPartner = UUID.randomUUID();
        UUID suspendedPartner = UUID.randomUUID();
        UUID revokedPartner = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id) VALUES (?), (?), (?)",
                expiredPartner, suspendedPartner, revokedPartner);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO user_account(id, association_id, external_subject, username, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                user, ASSOCIATION, "oidc-association", "association.user");
        jdbc.update("INSERT INTO association_relationship(source_association_id, target_association_id, status, allow_member_data, expires_at) VALUES (?, ?, 'ACTIVE', TRUE, ?)",
                ASSOCIATION, PARTNER, Instant.now().plusSeconds(3600));
        jdbc.update("INSERT INTO association_relationship(source_association_id, target_association_id, status, allow_member_data, expires_at) VALUES (?, ?, 'ACTIVE', TRUE, ?)",
                ASSOCIATION, expiredPartner, Instant.now().minusSeconds(60));
        jdbc.update("INSERT INTO association_relationship(source_association_id, target_association_id, status, allow_member_data, suspended_at) VALUES (?, ?, 'ACTIVE', TRUE, ?)",
                ASSOCIATION, suspendedPartner, Instant.now());
        jdbc.update("INSERT INTO association_relationship(source_association_id, target_association_id, status, allow_member_data, revoked_at) VALUES (?, ?, 'ACTIVE', TRUE, ?)",
                ASSOCIATION, revokedPartner, Instant.now());

        var actor = resolver.resolve(authentication("oidc-association", "association.user", "ASSOCIATION_ADMIN"));

        assertEquals(java.util.Set.of(PARTNER), actor.partnerAssociationIds());
    }

    @Test
    void rejectsUnboundAndIncompleteEnterpriseIdentitiesWithStableCodes() {
        ForbiddenException unbound = assertThrows(ForbiddenException.class,
                () -> resolver.resolve(authentication("missing", "missing", "ENTERPRISE_ADMIN")));
        assertEquals("IDENTITY_NOT_BOUND", unbound.code());

        jdbc.update("INSERT INTO user_account(id, external_subject, username, status) VALUES (?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), "incomplete", "incomplete.user");
        ForbiddenException incomplete = assertThrows(ForbiddenException.class,
                () -> resolver.resolve(authentication("incomplete", "incomplete.user", "ENTERPRISE_ADMIN")));
        assertEquals("IDENTITY_SCOPE_INCOMPLETE", incomplete.code());
    }

    @Test
    void rejectsInactiveAmbiguousAndAssociationBindingsWithoutAnAssociation() {
        jdbc.update("INSERT INTO user_account(id, external_subject, username, status) VALUES (?, ?, ?, 'INACTIVE')",
                UUID.randomUUID(), "inactive", "inactive.user");
        ForbiddenException inactive = assertThrows(ForbiddenException.class,
                () -> resolver.resolve(authentication("inactive", "inactive.user", "ENTERPRISE_ADMIN")));
        assertEquals("IDENTITY_NOT_BOUND", inactive.code());

        jdbc.update("INSERT INTO user_account(id, association_id, external_subject, username, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), ASSOCIATION, "ambiguous", "ambiguous.one");
        jdbc.update("INSERT INTO user_account(id, association_id, external_subject, username, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), ASSOCIATION, "ambiguous", "ambiguous.two");
        ForbiddenException ambiguous = assertThrows(ForbiddenException.class,
                () -> resolver.resolve(authentication("ambiguous", "ambiguous", "ASSOCIATION_ADMIN")));
        assertEquals("IDENTITY_NOT_BOUND", ambiguous.code());

        jdbc.update("INSERT INTO user_account(id, external_subject, username, status) VALUES (?, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), "association-incomplete", "association.incomplete");
        ForbiddenException incomplete = assertThrows(ForbiddenException.class,
                () -> resolver.resolve(authentication(
                        "association-incomplete", "association.incomplete", "ASSOCIATION_OPERATOR")));
        assertEquals("IDENTITY_SCOPE_INCOMPLETE", incomplete.code());
    }

    @Test
    void permitsUnboundSystemAdministratorOnlyAsTheBootstrapIdentity() {
        var actor = resolver.resolve(authentication("platform-bootstrap", "platform.admin", "SYSTEM_ADMIN"));
        assertNull(actor.associationId());
        assertNull(actor.enterpriseId());
        assertEquals(java.util.Set.of("SYSTEM_ADMIN"), actor.roles());
    }

    @Test
    void validatesSystemAdministratorAssociationAndEnterpriseContextHeaders() {
        jdbc.update("INSERT INTO association(id) VALUES (?)", ASSOCIATION);
        jdbc.update("INSERT INTO enterprise(id, association_id) VALUES (?, ?)", ENTERPRISE, ASSOCIATION);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DatabaseActorScopeResolver.ASSOCIATION_CONTEXT_HEADER, ASSOCIATION.toString());
        request.addHeader(DatabaseActorScopeResolver.ENTERPRISE_CONTEXT_HEADER, ENTERPRISE.toString());
        DatabaseActorScopeResolver scoped = new DatabaseActorScopeResolver(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()), request);

        var actor = scoped.resolve(authentication("platform-admin", "platform.admin", "SYSTEM_ADMIN"));

        assertEquals(ASSOCIATION, actor.associationId());
        assertEquals(ENTERPRISE, actor.enterpriseId());
    }

    @Test
    void rejectsSystemAdministratorEnterpriseOutsideSelectedAssociation() {
        UUID otherAssociation = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id) VALUES (?), (?)", ASSOCIATION, otherAssociation);
        jdbc.update("INSERT INTO enterprise(id, association_id) VALUES (?, ?)", ENTERPRISE, otherAssociation);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DatabaseActorScopeResolver.ASSOCIATION_CONTEXT_HEADER, ASSOCIATION.toString());
        request.addHeader(DatabaseActorScopeResolver.ENTERPRISE_CONTEXT_HEADER, ENTERPRISE.toString());
        DatabaseActorScopeResolver scoped = new DatabaseActorScopeResolver(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()), request);

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> scoped.resolve(authentication(
                        "platform-admin", "platform.admin", "SYSTEM_ADMIN")));
        assertEquals("SYSTEM_CONTEXT_FORBIDDEN", exception.code());
    }

    private static JwtAuthenticationToken authentication(String subject, String username, String role) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                java.util.Map.of("alg", "none"), java.util.Map.of("sub", subject, "preferred_username", username));
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), username);
    }
}
