package com.guanxian.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ecosystem.DemandView;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemPage;
import com.guanxian.platform.ecosystem.OfferingView;
import com.guanxian.platform.shared.security.ActorScope;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    EcosystemCatalogService catalog;

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
                fixture.partnerEnterpriseId(), fixture.actorAssociationId(),
                "PRODUCT", offeringId, "EXPIRED", Instant.now().minusSeconds(60));
        assertOfferingVisible(fixture, offeringId, false);

        jdbc.update("UPDATE enterprise_share_consent SET status='ACTIVE', expires_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), consentId);
        assertOfferingVisible(fixture, offeringId, true);
        assertCatalogSearch(fixture, "Partner Product", 1);
        assertCatalogSearch(fixture, "Hidden Offering Description", 0);
        assertCatalogSearch(fixture, "Partner Enterprise", 0);

        Instant expiredPolicyNow = Instant.now();
        jdbc.update("UPDATE association_share_policy SET valid_from=?, expires_at=? WHERE source_association_id=? AND target_association_id=? AND resource_type='PRODUCT'",
                java.sql.Timestamp.from(expiredPolicyNow.minusSeconds(120)),
                java.sql.Timestamp.from(expiredPolicyNow.minusSeconds(60)),
                fixture.partnerAssociationId(), fixture.actorAssociationId());
        assertOfferingVisible(fixture, offeringId, false);

        jdbc.update("UPDATE association_share_policy SET valid_from=?, expires_at=? WHERE source_association_id=? AND target_association_id=? AND resource_type='PRODUCT'",
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                fixture.partnerAssociationId(), fixture.actorAssociationId());
        jdbc.update("UPDATE enterprise_share_consent SET status='REVOKED', revoked_at=now() WHERE id=?", consentId);
        assertOfferingVisible(fixture, offeringId, false);
    }

    @Test
    void partnerCatalogPagesApplyAssociationAndPolicyGuardsBeforePagination() {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID offeringId = insertOffering(fixture.partnerEnterpriseId(), "PARTNERS");
        UUID demandId = insertDemand(fixture.partnerEnterpriseId(), "PARTNERS");
        insertPolicy(fixture, "PRODUCT", Instant.now().plusSeconds(3600));
        insertPolicy(fixture, "DEMAND", Instant.now().plusSeconds(3600));
        insertConsent(fixture, "PRODUCT", offeringId, Instant.now().plusSeconds(3600));
        insertConsent(fixture, "DEMAND", demandId, Instant.now().plusSeconds(3600));
        ActorScope partnerReader = new ActorScope(
                UUID.randomUUID(), "catalog-partner-reader", "catalog-partner-reader",
                fixture.actorAssociationId(), null, Set.of("ASSOCIATION_ADMIN"),
                Set.of(fixture.partnerAssociationId()));

        assertCatalogPage(catalog.offerings(partnerReader, null, false, 0, 1), offeringId);
        assertCatalogPage(catalog.demands(partnerReader, null, false, 0, 1), demandId);

        jdbc.update("UPDATE association SET status='INACTIVE' WHERE id=?", fixture.partnerAssociationId());
        assertEmptyCatalogPage(catalog.offerings(partnerReader, null, false, 0, 1));
        assertEmptyCatalogPage(catalog.demands(partnerReader, null, false, 0, 1));
        jdbc.update("UPDATE association SET status='ACTIVE' WHERE id=?", fixture.partnerAssociationId());

        jdbc.update("UPDATE association SET status='INACTIVE' WHERE id=?", fixture.actorAssociationId());
        assertEmptyCatalogPage(catalog.offerings(partnerReader, null, false, 0, 1));
        assertEmptyCatalogPage(catalog.demands(partnerReader, null, false, 0, 1));
        jdbc.update("UPDATE association SET status='ACTIVE' WHERE id=?", fixture.actorAssociationId());

        String visibleFieldsConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid=c.conrelid
                 WHERE t.relname='association_share_policy'
                   AND c.conname='association_share_policy_visible_fields_ck'
                """, String.class);
        jdbc.execute("ALTER TABLE association_share_policy "
                + "DROP CONSTRAINT association_share_policy_visible_fields_ck");
        try {
            // Simulate a row written before V17. Production writes cannot bypass the CHECK,
            // while the runtime authorization layer must still fail closed for legacy damage.
            jdbc.update("""
                    UPDATE association_share_policy
                       SET visible_fields='["name","unsupported"]'::jsonb
                     WHERE source_association_id=? AND target_association_id=?
                       AND resource_type='PRODUCT'
                    """, fixture.partnerAssociationId(), fixture.actorAssociationId());
            jdbc.update("""
                    UPDATE association_share_policy
                       SET visible_fields='["title",null]'::jsonb
                     WHERE source_association_id=? AND target_association_id=?
                       AND resource_type='DEMAND'
                    """, fixture.partnerAssociationId(), fixture.actorAssociationId());
            assertEmptyCatalogPage(catalog.offerings(partnerReader, null, false, 0, 1));
            assertEmptyCatalogPage(catalog.demands(partnerReader, null, false, 0, 1));

            jdbc.update("""
                    UPDATE association_share_policy
                       SET visible_fields='"name"'::jsonb
                     WHERE source_association_id=? AND target_association_id=?
                    """, fixture.partnerAssociationId(), fixture.actorAssociationId());
            assertEmptyCatalogPage(catalog.offerings(partnerReader, null, false, 0, 1));
            assertEmptyCatalogPage(catalog.demands(partnerReader, null, false, 0, 1));
        } finally {
            jdbc.update("""
                    UPDATE association_share_policy
                       SET visible_fields=CASE resource_type
                             WHEN 'PRODUCT' THEN '["name"]'::jsonb
                             WHEN 'DEMAND' THEN '["title"]'::jsonb
                             ELSE visible_fields
                           END
                     WHERE source_association_id=? AND target_association_id=?
                    """, fixture.partnerAssociationId(), fixture.actorAssociationId());
            jdbc.execute("ALTER TABLE association_share_policy ADD CONSTRAINT "
                    + "association_share_policy_visible_fields_ck " + visibleFieldsConstraint);
        }
    }

    @Test
    void sharedForeignDemandIsReadableButNeverEligibleForMatchGeneration() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID demandId = insertDemand(fixture.partnerEnterpriseId(), "PARTNERS");
        insertPolicy(fixture, "DEMAND", Instant.now().plusSeconds(3600));
        insertConsent(fixture, "DEMAND", demandId, Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/v1/demands/{id}", demandId).with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(demandId.toString()));
        mockMvc.perform(get("/api/v1/matches/generation-demands")
                        .with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(post("/api/v1/matches/demand/{id}/generate", demandId)
                        .with(actor(fixture.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMAND_SCOPE_VIOLATION"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM ecosystem_match WHERE demand_id=?",
                Integer.class, demandId));
    }

    @Test
    void partnerMemberIsVisibleOnlyThroughPolicyConsentAndSensitiveFieldsStayRedacted() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        jdbc.update("""
                UPDATE enterprise
                   SET unified_social_credit_code='91110000SENSITIVE',
                       contact_name='Sensitive Contact', contact_phone='13800000000',
                       description='Authorized introduction', address='Restricted address'
                 WHERE id=?
                """, fixture.partnerEnterpriseId());
        insertPolicy(fixture, "MEMBER", Instant.now().plusSeconds(3600));
        insertConsent(fixture, "MEMBER", fixture.partnerEnterpriseId(), Instant.now().plusSeconds(1800));

        String response = mockMvc.perform(get("/api/v1/members/{id}", fixture.partnerEnterpriseId())
                        .with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode member = objectMapper.readTree(response).path("data");
        assertEquals(fixture.partnerEnterpriseId().toString(), member.path("id").asText());
        assertEquals("Authorized introduction", member.path("introduction").asText());
        assertTrue(member.path("unifiedSocialCreditCode").isNull());
        assertTrue(member.path("contactName").isNull());
        assertTrue(member.path("contactPhone").isNull());
        assertTrue(member.path("address").isNull());
    }

    @Test
    void expiredActiveConsentIsMaterializedAuditedAndRegrantedThroughApi() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID offeringId = insertOffering(fixture.partnerEnterpriseId(), "PARTNERS");
        insertPolicy(fixture, "PRODUCT", Instant.now().plusSeconds(3600));
        String enterpriseSubject = "partner-enterprise-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(
                    id,association_id,enterprise_id,external_subject,username,display_name,status)
                VALUES (?,?,?,?,?,?,'ACTIVE')
                """, UUID.randomUUID(), fixture.partnerAssociationId(), fixture.partnerEnterpriseId(),
                enterpriseSubject, enterpriseSubject, "Partner Enterprise User");
        UUID expiredConsentId = UUID.randomUUID();
        jdbc.execute("ALTER TABLE enterprise_share_consent DISABLE TRIGGER enterprise_share_consent_materialize_expiry_trg");
        try {
            jdbc.update("""
                    INSERT INTO enterprise_share_consent(
                        id,enterprise_id,target_association_id,resource_type,resource_id,
                        status,granted_by_subject,expires_at)
                    VALUES (?,?,?,?,?,'ACTIVE','legacy-enterprise-user',?)
                    """, expiredConsentId, fixture.partnerEnterpriseId(), fixture.actorAssociationId(),
                    "PRODUCT", offeringId, java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
        } finally {
            jdbc.execute("ALTER TABLE enterprise_share_consent ENABLE TRIGGER enterprise_share_consent_materialize_expiry_trg");
        }

        mockMvc.perform(post("/api/v1/cross-associations/consents")
                        .with(enterpriseActor(enterpriseSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetAssociationId":"%s","resourceType":"PRODUCT",
                                 "resourceId":"%s","expiresAt":"%s"}
                                """.formatted(fixture.actorAssociationId(), offeringId,
                                Instant.now().plusSeconds(1800))))
                .andExpect(status().isCreated());

        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM enterprise_share_consent WHERE id=?", String.class, expiredConsentId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM enterprise_share_consent
                 WHERE enterprise_id=? AND target_association_id=?
                   AND resource_type='PRODUCT' AND resource_id=? AND status='ACTIVE'
                """, Integer.class, fixture.partnerEnterpriseId(), fixture.actorAssociationId(), offeringId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE association_id=? AND enterprise_id=?
                   AND action='ENTERPRISE_SHARE_CONSENT_EXPIRE' AND resource_id=?
                """, Integer.class, fixture.partnerAssociationId(), fixture.partnerEnterpriseId(),
                expiredConsentId.toString()));
    }

    @Test
    void relationshipLifecycleWritesVersionedAuditIntoBothAssociationDomains() throws Exception {
        UUID sourceAssociationId = UUID.randomUUID();
        UUID targetAssociationId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String sourceSubject = "relationship-source-" + suffix;
        String targetSubject = "relationship-target-" + suffix;
        jdbc.update("INSERT INTO association(id,name,status) VALUES (?,?,'ACTIVE'), (?,?,'ACTIVE')",
                sourceAssociationId, "Relationship Source " + suffix,
                targetAssociationId, "Relationship Target " + suffix);
        jdbc.update("""
                INSERT INTO user_account(id,association_id,external_subject,username,display_name,status)
                VALUES (?,?,?,?,?,'ACTIVE'), (?,?,?,?,?,'ACTIVE')
                """, UUID.randomUUID(), sourceAssociationId, sourceSubject, sourceSubject, "Source Reviewer",
                UUID.randomUUID(), targetAssociationId, targetSubject, targetSubject, "Target Reviewer");

        var requestResult = mockMvc.perform(post("/api/v1/cross-associations/access-requests")
                        .with(actor(sourceSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetAssociationId":"%s","reason":"versioned bilateral audit"}
                                """.formatted(targetAssociationId)))
                .andExpect(status().isCreated()).andReturn();
        UUID requestId = UUID.fromString(bodyData(
                requestResult.getResponse().getContentAsString()).path("id").asText());

        mockMvc.perform(put("/api/v1/cross-associations/access-requests/{id}/review", requestId)
                        .with(actor(targetSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"decision\":\"APPROVE\",\"allowMemberData\":true}"))
                .andExpect(status().isOk());

        String relationshipId = sourceAssociationId + ":" + targetAssociationId;
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='ASSOCIATION_RELATIONSHIP_ESTABLISH'
                   AND resource_id=? AND resource_version=0
                   AND association_id IN (?,?)
                """, Integer.class, relationshipId, sourceAssociationId, targetAssociationId));

        mockMvc.perform(put("/api/v1/cross-associations/relationships/{source}/{target}",
                        sourceAssociationId, targetAssociationId)
                        .with(actor(targetSubject))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SUSPEND\",\"reason\":\"maintenance\"}"))
                .andExpect(status().isOk());

        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='ASSOCIATION_RELATIONSHIP_SUSPEND'
                   AND resource_id=? AND resource_version=1
                   AND association_id IN (?,?)
                """, Integer.class, relationshipId, sourceAssociationId, targetAssociationId));

        mockMvc.perform(put("/api/v1/cross-associations/relationships/{source}/{target}",
                        sourceAssociationId, targetAssociationId)
                        .with(actor(targetSubject))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REVOKE\",\"reason\":\"partnership ended\"}"))
                .andExpect(status().isOk());

        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM association_relationship
                 WHERE source_association_id=? AND target_association_id=?
                   AND status='REVOKED'
                   AND suspended_at IS NULL
                   AND suspended_by_association_id IS NULL
                   AND suspended_by_subject IS NULL
                   AND revoked_at IS NOT NULL
                   AND revoked_by_subject=?
                   AND revoke_reason='partnership ended'
                """, Integer.class, sourceAssociationId, targetAssociationId, targetSubject));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action='ASSOCIATION_RELATIONSHIP_REVOKE'
                   AND resource_id=? AND resource_version=2
                   AND association_id IN (?,?)
                """, Integer.class, relationshipId, sourceAssociationId, targetAssociationId));

        mockMvc.perform(put("/api/v1/cross-associations/relationships/{source}/{target}",
                        sourceAssociationId, targetAssociationId)
                        .with(actor(targetSubject))
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"EXPIRE\"}"))
                .andExpect(status().isConflict());
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
    void externalMatchRequiresEveryParticipantConsentAndReturnsOnlyAuthorizedFields() throws Exception {
        Fixture fixture = fixture(Instant.now().plusSeconds(3600));
        UUID candidateEnterpriseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enterprise(id,association_id,name,visibility,status)
                VALUES (?,?,?,'PARTNERS','ACTIVE')
                """, candidateEnterpriseId, fixture.partnerAssociationId(), "Second Partner Enterprise");
        UUID demandId = insertDemand(fixture.partnerEnterpriseId(), "PARTNERS");
        UUID matchId = jdbc.queryForObject("""
                INSERT INTO ecosystem_match(
                    demand_id,candidate_enterprise_id,score,explanation,review_status,
                    demand_company_snapshot,demand_title_snapshot,scene_snapshot,
                    supplier_company_snapshot,solution,reasons,state)
                VALUES (?,?,88,'{}'::jsonb,'PENDING','Demand Owner','Authorized Demand Title',
                        'Hidden Scene','Hidden Supplier','Hidden Solution',
                        CAST('[\"Hidden Reason\"]' AS jsonb),'PENDING_CONFIRMATION')
                RETURNING id
                """, UUID.class, demandId, candidateEnterpriseId);
        insertPolicy(fixture, "MATCH", Instant.now().plusSeconds(3600));
        insertConsent(fixture, "MATCH", matchId, Instant.now().plusSeconds(1800));

        assertMatchVisible(fixture, matchId, false);
        UUID candidateConsent = insertConsent(
                candidateEnterpriseId, fixture.actorAssociationId(), "MATCH", matchId,
                Instant.now().plusSeconds(1800));
        assertMatchVisible(fixture, matchId, false);
        jdbc.update("""
                UPDATE ecosystem_match
                   SET state='RECOMMENDED', recommended_by_subject='fixture-reviewer',
                       recommended_at=now(), review_status='APPROVED',
                       version=version+1, updated_at=now()
                 WHERE id=?
                """, matchId);
        String response = mockMvc.perform(get("/api/v1/matches").with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode match = findById(objectMapper.readTree(response).path("data").path("items"), matchId);
        assertEquals("Authorized Demand Title", match.path("demandTitle").asText());
        assertEquals("RECOMMENDED", match.path("state").asText());
        assertTrue(match.path("solution").isNull());
        assertTrue(match.path("supplierCompany").isNull());
        assertTrue(match.path("score").isNull());
        assertTrue(match.path("reasons").isArray() && match.path("reasons").isEmpty());

        jdbc.update("""
                UPDATE association_share_policy
                   SET visible_fields='["demandTitle"]'::jsonb
                 WHERE source_association_id=? AND target_association_id=?
                   AND resource_type='MATCH'
                """, fixture.partnerAssociationId(), fixture.actorAssociationId());
        String redacted = mockMvc.perform(get("/api/v1/matches")
                        .with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode redactedMatch = findById(
                objectMapper.readTree(redacted).path("data").path("items"), matchId);
        assertTrue(redactedMatch.path("state").isNull());
        mockMvc.perform(get("/api/v1/matches")
                        .param("state", "RECOMMENDED")
                        .with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        jdbc.update("UPDATE enterprise_share_consent SET status='REVOKED', revoked_at=now() WHERE id=?",
                candidateConsent);
        assertMatchVisible(fixture, matchId, false);
    }

    @Test
    void bilateralConfirmationAndWorkflowStagesAreEnforcedByPostgres() throws Exception {
        WorkflowFixture fixture = workflowFixture();

        mockMvc.perform(get("/api/v1/matches")
                        .param("page", "0").param("size", "1")
                        .param("state", "pending_confirmation")
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(get("/api/v1/matches")
                        .param("page", "1").param("size", "1")
                        .param("state", "PENDING_CONFIRMATION")
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items").isEmpty());
        mockMvc.perform(get("/api/v1/matches")
                        .param("state", "NOT_A_STATE")
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MATCH_QUERY"));
        mockMvc.perform(get("/api/v1/matches")
                        .param("size", "0")
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MATCH_QUERY"));

        String supplierPending = mockMvc.perform(get("/api/v1/matches")
                        .with(enterpriseActor(fixture.supplierSubject())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(containsId(
                objectMapper.readTree(supplierPending).path("data").path("items"), fixture.matchId()));
        mockMvc.perform(get("/api/v1/matches/{id}", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/matches/{id}/confirm", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound());
        for (String child : new String[]{"invitations", "negotiations", "feedback", "outcomes"}) {
            mockMvc.perform(get("/api/v1/matches/{id}/" + child, fixture.matchId())
                            .with(enterpriseActor(fixture.supplierSubject())))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(post("/api/v1/matches/{id}/invitations", fixture.matchId())
                        .with(enterpriseActor(fixture.demandSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(fixture.supplierEnterpriseId())))
                .andExpect(status().isPreconditionFailed());

        JsonNode recommended = bodyData(mockMvc.perform(
                        post("/api/v1/matches/{id}/recommend", fixture.matchId())
                                .with(actor(fixture.reviewerSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("RECOMMENDED", recommended.path("state").asText());
        mockMvc.perform(get("/api/v1/matches/{id}/outcomes", fixture.matchId())
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        String supplierRecommended = mockMvc.perform(get("/api/v1/matches")
                        .with(enterpriseActor(fixture.supplierSubject())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(containsId(
                objectMapper.readTree(supplierRecommended).path("data").path("items"), fixture.matchId()));
        mockMvc.perform(get("/api/v1/matches/{id}", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fixture.matchId().toString()))
                .andExpect(jsonPath("$.data.allowedActions").isArray())
                .andExpect(result -> assertEquals("\"1\"",
                        result.getResponse().getHeader(HttpHeaders.ETAG)));
        for (String child : new String[]{"invitations", "negotiations", "feedback", "outcomes"}) {
            mockMvc.perform(get("/api/v1/matches/{id}/" + child, fixture.matchId())
                            .with(enterpriseActor(fixture.supplierSubject())))
                    .andExpect(status().isOk());
        }

        JsonNode demandConfirmation = bodyData(mockMvc.perform(
                        post("/api/v1/matches/{id}/confirm", fixture.matchId())
                                .with(enterpriseActor(fixture.demandSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("PARTIALLY_CONFIRMED", demandConfirmation.path("state").asText());
        assertFalse(demandConfirmation.path("demandConfirmedAt").isNull());

        JsonNode bilateral = bodyData(mockMvc.perform(
                        post("/api/v1/matches/{id}/confirm", fixture.matchId())
                                .with(enterpriseActor(fixture.supplierSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("CONFIRMED", bilateral.path("state").asText());
        assertFalse(bilateral.path("candidateConfirmedAt").isNull());

        var inviteResult = mockMvc.perform(
                        post("/api/v1/matches/{id}/invitations", fixture.matchId())
                                .with(enterpriseActor(fixture.demandSubject()))
                                .header(HttpHeaders.IF_MATCH, "\"3\"")
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

        var supplierFeedback0 = mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"合作达成\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("\"0\"", supplierFeedback0.getResponse().getHeader(HttpHeaders.ETAG));
        JsonNode supplierFeedbackBody = bodyData(supplierFeedback0.getResponse().getContentAsString());
        assertTrue(supplierFeedbackBody.path("submittedBySubject").isNull());

        var supplierFeedback1 = mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"一次复核\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("\"1\"", supplierFeedback1.getResponse().getHeader(HttpHeaders.ETAG));
        var supplierFeedback2 = mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"二次复核\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("\"2\"", supplierFeedback2.getResponse().getHeader(HttpHeaders.ETAG));
        mockMvc.perform(post("/api/v1/matches/{id}/feedback", fixture.matchId())
                        .with(enterpriseActor(fixture.supplierSubject()))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"outcome\":\"SUCCESS\",\"comment\":\"陈旧版本\"}"))
                .andExpect(status().isPreconditionFailed());

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

        String readyResponse = mockMvc.perform(get("/api/v1/matches")
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode readyMatch = findById(
                objectMapper.readTree(readyResponse).path("data").path("items"), fixture.matchId());
        assertTrue(containsText(readyMatch.path("allowedActions"), "ARCHIVE"));

        mockMvc.perform(post("/api/v1/matches/{id}/outcomes", fixture.matchId())
                        .with(actor(fixture.reviewerSubject()))
                        .header(HttpHeaders.IF_MATCH, etag(matchVersion(fixture.matchId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outcomeJson()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/matches/{id}/outcomes", fixture.matchId())
                        .with(actor(fixture.reviewerSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("合作成果"));

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
                INSERT INTO product_service(id,enterprise_id,name,kind,description,visibility,status,version)
                VALUES (?,?,'Partner Product','PRODUCT','Hidden Offering Description',?,'ACTIVE',0)
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
        String reviewerSubject = "reviewer-" + suffix;
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
                VALUES (?,?,?,?,?,?,'ACTIVE'), (?,?,?,?,?,?,'ACTIVE'),
                       (?,?,?,?,?,?,'ACTIVE')
                """, UUID.randomUUID(), associationId, demandEnterpriseId, demandSubject,
                demandSubject, "Demand User", UUID.randomUUID(), associationId,
                supplierEnterpriseId, supplierSubject, supplierSubject, "Supplier User",
                UUID.randomUUID(), associationId, null, reviewerSubject,
                reviewerSubject, "Association Reviewer");
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
                matchId, demandEnterpriseId, supplierEnterpriseId,
                demandSubject, supplierSubject, reviewerSubject);
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
        String visibleFields = switch (resourceType) {
            case "MEMBER" -> "[\"name\",\"introduction\"]";
            case "DEMAND" -> "[\"title\"]";
            case "MATCH" -> "[\"demandTitle\",\"state\"]";
            default -> "[\"name\"]";
        };
        jdbc.update("""
                INSERT INTO association_share_policy(
                    source_association_id,target_association_id,resource_type,visible_fields,
                    status,valid_from,expires_at,created_by_subject)
                VALUES (?,?,?,CAST(? AS jsonb),'ACTIVE',now(),?,'partner-admin')
                """, fixture.partnerAssociationId(), fixture.actorAssociationId(), resourceType, visibleFields,
                java.sql.Timestamp.from(expiresAt));
    }

    private UUID insertConsent(
            Fixture fixture, String resourceType, UUID resourceId, Instant expiresAt) {
        return insertConsent(fixture.partnerEnterpriseId(), fixture.actorAssociationId(),
                resourceType, resourceId, expiresAt);
    }

    private UUID insertConsent(
            UUID enterpriseId,
            UUID targetAssociationId,
            String resourceType,
            UUID resourceId,
            Instant expiresAt) {
        return insertConsent(enterpriseId, targetAssociationId, resourceType, resourceId, "ACTIVE", expiresAt);
    }

    private UUID insertConsent(
            UUID enterpriseId,
            UUID targetAssociationId,
            String resourceType,
            UUID resourceId,
            String status,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enterprise_share_consent(
                    id,enterprise_id,target_association_id,resource_type,resource_id,
                    status,granted_by_subject,expires_at)
                VALUES (?,?,?,?,?,?,'partner-enterprise-admin',?)
                """, id, enterpriseId, targetAssociationId, resourceType, resourceId,
                status, java.sql.Timestamp.from(expiresAt));
        return id;
    }

    private void assertMatchVisible(Fixture fixture, UUID id, boolean expected) throws Exception {
        String response = mockMvc.perform(get("/api/v1/matches").with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(response).path("data");
        assertEquals(expected ? 1 : 0, page.path("total").asInt());
        assertEquals(expected, containsId(page.path("items"), id));
    }

    private void assertOfferingVisible(Fixture fixture, UUID id, boolean expected) throws Exception {
        String response = mockMvc.perform(get("/api/v1/offerings").with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(response).path("data").path("items");
        assertEquals(expected, containsId(items, id));
        if (expected) {
            JsonNode offering = findById(items, id);
            assertEquals("Partner Product", offering.path("name").asText());
            assertTrue(offering.path("enterpriseName").isNull());
            assertTrue(offering.path("description").isNull());
            assertTrue(offering.path("scenarios").isArray() && offering.path("scenarios").isEmpty());
            assertTrue(offering.path("qualifications").isArray() && offering.path("qualifications").isEmpty());
        }
    }

    private void assertCatalogSearch(Fixture fixture, String query, int expected) throws Exception {
        String response = mockMvc.perform(get("/api/v1/offerings")
                        .param("query", query).with(actor(fixture.subject())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(response).path("data");
        assertEquals(expected, page.path("total").asInt());
        assertEquals(expected, page.path("items").size());
    }

    private static void assertCatalogPage(EcosystemPage<?> page, UUID expectedId) {
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        Object item = page.items().getFirst();
        UUID actualId = switch (item) {
            case OfferingView offering -> offering.id();
            case DemandView demand -> demand.id();
            default -> throw new AssertionError("unexpected catalog resource: " + item);
        };
        assertEquals(expectedId, actualId);
    }

    private static void assertEmptyCatalogPage(EcosystemPage<?> page) {
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    private static JsonNode findById(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("id").asText())) return value;
        }
        throw new AssertionError("expected resource was not found: " + id);
    }

    private static boolean containsId(JsonNode values, UUID id) {
        for (JsonNode value : values) {
            if (id.toString().equals(value.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
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
            String supplierSubject,
            String reviewerSubject) {
    }
}
