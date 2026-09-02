package com.guanxian.platform.ecosystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresEcosystemStoreScopeSqlTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID ENTERPRISE_ID = UUID.randomUUID();

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void deletedCatalogRowsRequireAnExplicitAdministratorRole() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemCatalogStore store = new PostgresEcosystemCatalogStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.listOfferings(actor("ENTERPRISE_MEMBER", ENTERPRISE_ID), null, true, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("AND p.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("AND e.status='ACTIVE' AND e.deleted_at IS NULL"));

        clearInvocations(jdbc);
        store.listOfferings(actor("ENTERPRISE_ADMIN", ENTERPRISE_ID), null, true, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains(
                "AND (p.deleted_at IS NULL OR p.enterprise_id=:enterpriseId)"));

        clearInvocations(jdbc);
        store.listOfferings(actor("ASSOCIATION_ADMIN", null), null, true, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertFalse(sql.getValue().contains(
                "AND (p.deleted_at IS NULL OR p.enterprise_id=:enterpriseId)"));
        assertFalse(sql.getValue().contains("AND e.status='ACTIVE' AND e.deleted_at IS NULL"));
    }

    @Test
    void everyPostgresMatchMutationCarriesActorWriteScope() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemMatchStore store = new PostgresEcosystemMatchStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ActorScope associationAdmin = actor("ASSOCIATION_ADMIN", null);
        ActorScope enterpriseAdmin = actor("ENTERPRISE_ADMIN", ENTERPRISE_ID);
        UUID matchId = UUID.randomUUID();

        store.recommend(matchId, 0, associationAdmin);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("scope_enterprise.association_id=:associationId"));

        clearInvocations(jdbc);
        store.transition(matchId, 0, MatchLifecycle.CLOSED, "closed", enterpriseAdmin);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains(
                "scope_demand.enterprise_id=:contextEnterpriseId"));
        assertTrue(sql.getValue().contains(
                "ecosystem_match.candidate_enterprise_id=:contextEnterpriseId"));

        clearInvocations(jdbc);
        store.confirm(matchId, 0, ENTERPRISE_ID, enterpriseAdmin);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains(":enterpriseId=:contextEnterpriseId"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void matchSqlNeverExpandsBeyondTheActorPartnerSet() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemMatchStore store = new PostgresEcosystemMatchStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.list(actor("ASSOCIATION_ADMIN", null), null, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertFalse(sql.getValue().contains("association_relationship"));

        clearInvocations(jdbc);
        store.list(actor("ASSOCIATION_ADMIN", null, Set.of(UUID.randomUUID())), null, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("association_relationship"));
        assertTrue(sql.getValue().contains("association_id IN (:partnerAssociationIds)"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void matchListAndCountKeepAssociationHistoryWhileSystemScopeRequiresActiveParticipants() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemMatchStore store = new PostgresEcosystemMatchStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ActorScope associationAdmin = actor("ASSOCIATION_ADMIN", null);

        store.list(associationAdmin, null, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertHistoricalDemandOwnerScope(sql.getValue());

        clearInvocations(jdbc);
        store.count(associationAdmin, null);
        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertHistoricalDemandOwnerScope(sql.getValue());

        clearInvocations(jdbc);
        store.list(actor("SYSTEM_ADMIN", null), null, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertActiveDemandOwnerScope(sql.getValue());
        assertTrue(sql.getValue().contains("ce.status='ACTIVE' AND ce.deleted_at IS NULL"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void partnerCatalogListAndCountShareFailClosedAuthorizationPredicates() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemCatalogStore store = new PostgresEcosystemCatalogStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ActorScope partnerReader = actor(
                "ASSOCIATION_ADMIN", null, Set.of(UUID.randomUUID()));

        store.listOfferings(partnerReader, null, false, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertPartnerCatalogPredicate(sql.getValue(), "name", "qualifications");

        clearInvocations(jdbc);
        store.countOfferings(partnerReader, null, false);
        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertPartnerCatalogPredicate(sql.getValue(), "name", "qualifications");

        clearInvocations(jdbc);
        store.listDemands(partnerReader, null, false, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertPartnerCatalogPredicate(sql.getValue(), "title", "responseDeadline");

        clearInvocations(jdbc);
        store.countDemands(partnerReader, null, false);
        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertPartnerCatalogPredicate(sql.getValue(), "title", "responseDeadline");
    }

    @Test
    void catalogReviewUpdatesCarryAssociationTenantPredicate() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemCatalogStore store = new PostgresEcosystemCatalogStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ActorScope reviewer = actor("ASSOCIATION_ADMIN", null);

        store.transitionOffering(UUID.randomUUID(), 0, "ACTIVE", reviewer);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("review_enterprise.association_id=:associationId"));
        assertTrue(sql.getValue().contains("review_association.status='ACTIVE'"));

        clearInvocations(jdbc);
        store.transitionDemand(UUID.randomUUID(), 0, "OPEN", null, reviewer);
        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains("review_enterprise.association_id=:associationId"));
        assertTrue(sql.getValue().contains("review_association.status='ACTIVE'"));
    }

    @Test
    void responseWindowSqlRejectsExpiredAndUnsupportedDirectedDemands() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemCatalogStore store = new PostgresEcosystemCatalogStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.isDemandOpenForResponse(UUID.randomUUID());

        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Boolean.class));
        assertTrue(sql.getValue().contains("status='OPEN'"));
        assertTrue(sql.getValue().contains("visibility <> 'DIRECTED'"));
        assertTrue(sql.getValue().contains("response_deadline > now()"));
        assertTrue(sql.getValue().contains("deleted_at IS NULL"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void workflowHistoryKeepsLifecycleFilterOnlyForEnterpriseParticipants() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresEcosystemWorkflowStore store = new PostgresEcosystemWorkflowStore(jdbc);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        UUID matchId = UUID.randomUUID();

        store.invitations(matchId, actor("ENTERPRISE_ADMIN", ENTERPRISE_ID));
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("read_candidate.status='ACTIVE'"));

        clearInvocations(jdbc);
        store.invitations(matchId, actor("ASSOCIATION_ADMIN", null));
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertFalse(sql.getValue().contains("read_candidate.status='ACTIVE'"));
    }

    private static ActorScope actor(String role, UUID enterpriseId) {
        return actor(role, enterpriseId, Set.of());
    }

    private static ActorScope actor(String role, UUID enterpriseId, Set<UUID> partners) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + role, role.toLowerCase(),
                ASSOCIATION_ID, enterpriseId, Set.of(role), partners);
    }

    private static void assertPartnerCatalogPredicate(
            String sql, String requiredField, String allowedField) {
        assertTrue(sql.contains("e.association_id<>:associationId"));
        assertTrue(sql.contains("source_association.status='ACTIVE'"));
        assertTrue(sql.contains("target_association.status='ACTIVE'"));
        assertTrue(sql.contains("transaction_timestamp()"));
        assertTrue(sql.contains("jsonb_typeof(sp.visible_fields)='array'"));
        assertTrue(sql.contains("sp.visible_fields @> CAST('[\"" + requiredField + "\"]' AS jsonb)"));
        assertTrue(sql.contains("sp.visible_fields <@ CAST('[\"enterpriseName\""));
        assertTrue(sql.contains("\"" + allowedField + "\"]' AS jsonb)"));
        assertTrue(sql.contains("jsonb_typeof(invalid_sp.visible_fields)='array'"));
    }

    private static void assertActiveDemandOwnerScope(String sql) {
        assertTrue(sql.contains("de.association_id=:associationId"));
        assertTrue(sql.contains("de.status='ACTIVE' AND de.deleted_at IS NULL"));
    }

    private static void assertHistoricalDemandOwnerScope(String sql) {
        assertTrue(sql.contains("de.association_id=:associationId"));
        assertFalse(sql.contains("de.status='ACTIVE' AND de.deleted_at IS NULL"));
    }
}
