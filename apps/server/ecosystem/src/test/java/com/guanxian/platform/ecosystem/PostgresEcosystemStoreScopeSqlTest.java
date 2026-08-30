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
        return new ActorScope(
                UUID.randomUUID(), "subject-" + role, role.toLowerCase(),
                ASSOCIATION_ID, enterpriseId, Set.of(role), Set.of());
    }
}
