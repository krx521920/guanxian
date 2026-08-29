package com.guanxian.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=jwt",
        "guanxian.security.jwt.issuer-uri=https://identity.example.com/realms/guanxian",
        "guanxian.security.jwt.jwk-set-uri=https://identity.example.com/realms/guanxian/certs"
})
@AutoConfigureMockMvc
class PostgresCrossTenantAuthorizationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void expiredRelationshipDoesNotGrantPartnerMemberVisibility() throws Exception {
        Fixture fixture = fixture(Instant.now().minusSeconds(60));

        String response = mockMvc.perform(get("/api/v1/members").with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(containsId(objectMapper.readTree(response).path("data"), fixture.partnerEnterpriseId()));
    }

    @Test
    void partnerResourceRequiresCurrentPolicyAndEnterpriseConsent() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID offeringId = insertOffering(fixture.partnerEnterpriseId(), "PARTNERS");

        assertOfferingVisible(fixture, offeringId, false);
        insertPolicy(fixture, "PRODUCT", Instant.now().plusSeconds(3600));
        assertOfferingVisible(fixture, offeringId, false);
        UUID consentId = insertConsent(
                fixture, "PRODUCT", offeringId, Instant.now().minusSeconds(60));
        assertOfferingVisible(fixture, offeringId, false);

        jdbc.update("UPDATE enterprise_share_consent SET expires_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), consentId);
        assertOfferingVisible(fixture, offeringId, true);

        jdbc.update("UPDATE association_share_policy SET expires_at=? WHERE source_association_id=? AND target_association_id=? AND resource_type='PRODUCT'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                fixture.partnerAssociationId(), fixture.actorAssociationId());
        assertOfferingVisible(fixture, offeringId, false);

        jdbc.update("UPDATE association_share_policy SET expires_at=? WHERE source_association_id=? AND target_association_id=? AND resource_type='PRODUCT'",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                fixture.partnerAssociationId(), fixture.actorAssociationId());
        jdbc.update("UPDATE enterprise_share_consent SET status='REVOKED', revoked_at=now() WHERE id=?", consentId);
        assertOfferingVisible(fixture, offeringId, false);
    }

    @Test
    void partnerAssociationCannotMutateCatalogOrGenerateMatchesForSourceTenant() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID offeringId = insertOffering(fixture.partnerEnterpriseId(), "PARTNERS");
        UUID demandId = insertDemand(fixture.partnerEnterpriseId(), "PARTNERS");
        insertPolicy(fixture, "PRODUCT", Instant.now().plusSeconds(3600));
        insertPolicy(fixture, "DEMAND", Instant.now().plusSeconds(3600));
        insertConsent(fixture, "PRODUCT", offeringId, Instant.now().plusSeconds(3600));
        insertConsent(fixture, "DEMAND", demandId, Instant.now().plusSeconds(3600));

        assertOfferingVisible(fixture, offeringId, true);
        mockMvc.perform(delete("/api/v1/offerings/{id}", offeringId)
                        .with(actor(fixture.subject()))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSOCIATION_SCOPE_VIOLATION"));

        mockMvc.perform(post("/api/v1/matches/demand/{id}/generate", demandId)
                        .with(actor(fixture.subject())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMAND_SCOPE_VIOLATION"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecosystem_match WHERE demand_id=?", Integer.class, demandId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_id IN (?, ?)",
                Integer.class, offeringId.toString(), demandId.toString()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_service WHERE id=? AND deleted_at IS NOT NULL",
                Integer.class, offeringId));
    }

    @Test
    void bilateralConfirmationAndWorkflowStagesAreEnforcedByPostgres() throws Exception {
        WorkflowFixture fixture = workflowFixture();

        mockMvc.perform(post("/api/v1/matches/{id}/invitations", fixture.matchId())
                        .with(enterpriseActor(fixture.demandSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(fixture.supplierEnterpriseId())))
                .andExpect(status().isPreconditionFailed());

        JsonNode demandConfirmation = bodyData(mockMvc.perform(
                        post("/api/v1/matches/{id}/confirm", fixture.matchId())
                                .with(enterpriseActor(fixture.demandSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("PARTIALLY_CONFIRMED", demandConfirmation.path("state").asText());
        assertFalse(demandConfirmation.path("demandConfirmedAt").isNull());

        JsonNode bilateral = bodyData(mockMvc.perform(
                        post("/api/v1/matches/{id}/confirm", fixture.matchId())
                                .with(enterpriseActor(fixture.supplierSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("CONFIRMED", bilateral.path("state").asText());
        assertFalse(bilateral.path("candidateConfirmedAt").isNull());

        var inviteResult = mockMvc.perform(
                        post("/api/v1/matches/{id}/invitations", fixture.matchId())
                                .with(enterpriseActor(fixture.demandSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"2\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invitationJson(fixture.supplierEnterpriseId())))
                .andExpect(status().isOk()).andReturn();
        JsonNode invitation = bodyData(inviteResult.getResponse().getContentAsString());
        assertEquals("INVITED", matchState(fixture.matchId()));

        mockMvc.perform(post("/api/v1/matches/invitations/{id}/respond",
                        UUID.fromString(invitation.path("id").asText()))
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, inviteResult.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true,\"comment\":\"进入洽谈\"}"))
                .andExpect(status().isOk());
        assertEquals("NEGOTIATING", matchState(fixture.matchId()));

        mockMvc.perform(post("/api/v1/matches/{id}/negotiations", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, etag(matchVersion(fixture.matchId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negotiationJson("TECHNICAL_EXCHANGE", "不能跳级")))
                .andExpect(status().isPreconditionFailed());

        for (String stage : new String[]{
                "INITIAL_CONTACT", "TECHNICAL_EXCHANGE", "COMMERCIAL_NEGOTIATION",
                "CONTRACTING", "CONTRACT_SIGNED"}) {
            mockMvc.perform(post("/api/v1/matches/{id}/negotiations", fixture.matchId())
                            .with(enterpriseActor(fixture.supplierSubject()))
                            .header(HttpHeaders.IF_MATCH, etag(matchVersion(fixture.matchId())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(negotiationJson(stage, "阶段推进：" + stage)))
                    .andExpect(status().isOk());
        }
        assertEquals("OUTCOME_PENDING", matchState(fixture.matchId()));

        mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"合作达成\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/matches/{id}/outcomes", fixture.matchId())
                        .with(enterpriseActor(fixture.demandSubject()))
                        .header(HttpHeaders.IF_MATCH, etag(matchVersion(fixture.matchId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outcomeJson()))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.demandSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"验收通过\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/matches/{id}/outcomes", fixture.matchId())
                        .with(enterpriseActor(fixture.demandSubject()))
                        .header(HttpHeaders.IF_MATCH, etag(matchVersion(fixture.matchId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outcomeJson()))
                .andExpect(status().isOk());

        assertEquals("ARCHIVED", matchState(fixture.matchId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM match_feedback WHERE match_id=?", Integer.class, fixture.matchId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outcome_archive WHERE match_id=? AND deleted_at IS NULL",
                Integer.class, fixture.matchId()));
    }

    private Fixture fixture(Instant relationshipExpiry) {
        UUID actorAssociationId = UUID.randomUUID();
        UUID partnerAssociationId = UUID.randomUUID();
        UUID partnerEnterpriseId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String subject = "actor-" + suffix;
        jdbc.update("INSERT INTO association(id,name,status) VALUES (?,?,'ACTIVE')",
                actorAssociationId, "Actor Association " + suffix);
        jdbc.update("INSERT INTO association(id,name,status) VALUES (?,?,'ACTIVE')",
                partnerAssociationId, "Partner Association " + suffix);
        jdbc.update("""
                INSERT INTO enterprise(id,association_id,name,visibility,status)
                VALUES (?,?,?,'PARTNERS','ACTIVE')
                """, partnerEnterpriseId, partnerAssociationId, "Partner Enterprise " + suffix);
        jdbc.update("""
                INSERT INTO user_account(id,association_id,external_subject,username,display_name,status)
                VALUES (?,?,?,?,?,'ACTIVE')
                """, actorUserId, actorAssociationId, subject, "user-" + suffix, "Actor User");
        jdbc.update("""
                INSERT INTO association_relationship(
                    source_association_id,target_association_id,status,allow_member_data,expires_at)
                VALUES (?,?,'ACTIVE',TRUE,?)
                """, actorAssociationId, partnerAssociationId,
                java.sql.Timestamp.from(relationshipExpiry));
        return new Fixture(actorAssociationId, partnerAssociationId, partnerEnterpriseId, subject);
    }

    private UUID insertOffering(UUID enterpriseId, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO product_service(id,enterprise_id,name,kind,visibility,status,version)
                VALUES (?,?,'Partner Product','PRODUCT',?,'ACTIVE',0)
                """, id, enterpriseId, visibility);
        return id;
    }

    private WorkflowFixture workflowFixture() {
        UUID associationId = UUID.randomUUID();
        UUID demandEnterpriseId = UUID.randomUUID();
        UUID supplierEnterpriseId = UUID.randomUUID();
        UUID demandId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String demandSubject = "demand-" + suffix;
        String supplierSubject = "supplier-" + suffix;
        jdbc.update("INSERT INTO association(id,name,status) VALUES (?,?,'ACTIVE')",
                associationId, "Workflow Association " + suffix);
        jdbc.update("""
                INSERT INTO enterprise(id,association_id,name,visibility,status)
                VALUES (?,?,?,'MEMBERS','ACTIVE'), (?,?,?,'MEMBERS','ACTIVE')
                """, demandEnterpriseId, associationId, "Demand Enterprise " + suffix,
                supplierEnterpriseId, associationId, "Supplier Enterprise " + suffix);
        jdbc.update("""
                INSERT INTO user_account(
                    id,association_id,enterprise_id,external_subject,username,display_name,status)
                VALUES (?,?,?,?,?,?,'ACTIVE'), (?,?,?,?,?,?,'ACTIVE')
                """, UUID.randomUUID(), associationId, demandEnterpriseId, demandSubject,
                demandSubject, "Demand User", UUID.randomUUID(), associationId,
                supplierEnterpriseId, supplierSubject, supplierSubject, "Supplier User");
        jdbc.update("""
                INSERT INTO cooperation_demand(
                    id,enterprise_id,title,description,visibility,status,version)
                VALUES (?,?,'Workflow Demand','Workflow lifecycle','MEMBERS','OPEN',0)
                """, demandId, demandEnterpriseId);
        jdbc.update("""
                INSERT INTO ecosystem_match(
                    id,demand_id,candidate_enterprise_id,score,explanation,review_status,
                    demand_company_snapshot,demand_title_snapshot,supplier_company_snapshot,
                    solution,reasons,state,version)
                VALUES (?,?,?,92,'{}'::jsonb,'PENDING','Demand Enterprise','Workflow Demand',
                        'Supplier Enterprise','Workflow Solution','[]'::jsonb,
                        'PENDING_CONFIRMATION',0)
                """, matchId, demandId, supplierEnterpriseId);
        return new WorkflowFixture(
                matchId, demandEnterpriseId, supplierEnterpriseId, demandSubject, supplierSubject);
    }

    private JsonNode bodyData(String response) throws Exception {
        return objectMapper.readTree(response).path("data");
    }

    private String matchState(UUID matchId) {
        return jdbc.queryForObject("SELECT state FROM ecosystem_match WHERE id=?", String.class, matchId);
    }

    private long matchVersion(UUID matchId) {
        Long value = jdbc.queryForObject(
                "SELECT version FROM ecosystem_match WHERE id=?", Long.class, matchId);
        return value == null ? -1 : value;
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static String invitationJson(UUID recipientEnterpriseId) {
        return """
                {"recipientEnterpriseId":"%s","invitationType":"ENTERPRISE","message":"请确认合作意向"}
                """.formatted(recipientEnterpriseId);
    }

    private static String negotiationJson(String stage, String summary) {
        return """
                {"stage":"%s","summary":"%s"}
                """.formatted(stage, summary);
    }

    private static String outcomeJson() {
        return """
                {"title":"合作成果","summary":"双方完成合同签署与验收","resultType":"CONTRACT","visibility":"ASSOCIATION"}
                """;
    }

    private UUID insertDemand(UUID enterpriseId, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cooperation_demand(
                    id,enterprise_id,title,description,visibility,status,version)
                VALUES (?,?,'Partner Demand','Partner demand description',?,'OPEN',0)
                """, id, enterpriseId, visibility);
        return id;
    }

    private void insertPolicy(Fixture fixture, String resourceType, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO association_share_policy(
                    source_association_id,target_association_id,resource_type,visible_fields,
                    status,valid_from,expires_at,created_by_subject)
                VALUES (?,?,?,CAST('[\"name\"]' AS jsonb),'ACTIVE',now(),?,'partner-admin')
                """, fixture.partnerAssociationId(), fixture.actorAssociationId(), resourceType,
                java.sql.Timestamp.from(expiresAt));
    }

    private UUID insertConsent(
            Fixture fixture, String resourceType, UUID resourceId, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enterprise_share_consent(
                    id,enterprise_id,target_association_id,resource_type,resource_id,
                    status,granted_by_subject,expires_at)
                VALUES (?,?,?,?,?,'ACTIVE','partner-enterprise-admin',?)
                """, id, fixture.partnerEnterpriseId(), fixture.actorAssociationId(), resourceType,
                resourceId, java.sql.Timestamp.from(expiresAt));
        return id;
    }

    private void assertOfferingVisible(Fixture fixture, UUID id, boolean expected) throws Exception {
        String response = mockMvc.perform(get("/api/v1/offerings").with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(expected, containsId(
                objectMapper.readTree(response).path("data").path("items"), id));
    }

    private static boolean containsId(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static RequestPostProcessor actor(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ASSOCIATION_ADMIN"),
                        new SimpleGrantedAuthority("MEMBER_READ"),
                        new SimpleGrantedAuthority("ENTERPRISE_WRITE"),
                        new SimpleGrantedAuthority("MEMBER_REVIEW"),
                        new SimpleGrantedAuthority("MATCH_REQUEST"));
    }

    private static RequestPostProcessor enterpriseActor(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("preferred_username", subject))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ENTERPRISE_ADMIN"),
                        new SimpleGrantedAuthority("ENTERPRISE_WRITE"),
                        new SimpleGrantedAuthority("MATCH_REQUEST"));
    }

    private record Fixture(
            UUID actorAssociationId,
            UUID partnerAssociationId,
            UUID partnerEnterpriseId,
            String subject) {
    }

    private record WorkflowFixture(
            UUID matchId,
            UUID demandEnterpriseId,
            UUID supplierEnterpriseId,
            String demandSubject,
            String supplierSubject) {
    }
}
