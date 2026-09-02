package com.guanxian.platform.collaboration;

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

class PostgresCollaborationStoreScopeSqlTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID ENTERPRISE_ID = UUID.randomUUID();

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enterpriseParticipantsRequireOperationalRecordsWhileAdministratorsReadHistory() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresCollaborationStore store = new PostgresCollaborationStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.list(actor("ENTERPRISE_ADMIN", ENTERPRISE_ID), null, false, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("actor_enterprise.status='ACTIVE'"));
        assertFalse(sql.getValue().contains("lde.status='ACTIVE'"));

        clearInvocations(jdbc);
        store.list(actor("ASSOCIATION_ADMIN", null), null, false, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertFalse(sql.getValue().contains("oe.status='ACTIVE'"));
        assertFalse(sql.getValue().contains("lde.status='ACTIVE'"));

        clearInvocations(jdbc);
        store.list(actor("SYSTEM_ADMIN", ENTERPRISE_ID), null, false, 0, 20);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("c.enterprise_id=:enterpriseId"));
        assertFalse(sql.getValue().contains("oe.status='ACTIVE'"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void linkedMatchScopeIncludesBothTenantParticipantsWithoutWideningOrdinaryItems() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresCollaborationStore store = new PostgresCollaborationStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.list(actor("ENTERPRISE_ADMIN", ENTERPRISE_ID), null, false, 0, 20);

        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("c.match_id IS NULL AND c.association_id=:associationId"));
        assertTrue(sql.getValue().contains("scope_demand.enterprise_id=:enterpriseId"));
        assertTrue(sql.getValue().contains("scope_candidate_enterprise.association_id=:associationId"));
        assertTrue(sql.getValue().contains("scope_match.candidate_enterprise_id=:enterpriseId"));
        assertFalse(sql.getValue().contains("scope_match.deleted_at IS NULL"));
        assertFalse(sql.getValue().contains("scope_demand.deleted_at IS NULL"));
    }

    @Test
    void newLinksRequireBilateralConfirmationAndANonTerminalState() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresCollaborationStore store = new PostgresCollaborationStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        UUID matchId = UUID.randomUUID();

        store.canLinkMatch(matchId, ASSOCIATION_ID, ENTERPRISE_ID);

        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Boolean.class));
        assertTrue(sql.getValue().contains(
                "m.state IN ('CONFIRMED','INVITED','NEGOTIATING','OUTCOME_PENDING')"));
        assertTrue(sql.getValue().contains("m.demand_confirmed_at IS NOT NULL"));
        assertTrue(sql.getValue().contains("m.candidate_confirmed_at IS NOT NULL"));
        assertTrue(sql.getValue().contains("m.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("d.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("ce.association_id=:associationId"));
        assertFalse(sql.getValue().contains("'ARCHIVED'"));
        assertFalse(sql.getValue().contains("'CLOSED'"));

        clearInvocations(jdbc);
        store.canAccessLinkedMatch(matchId, ASSOCIATION_ID, ENTERPRISE_ID);
        verify(jdbc).queryForObject(
                sql.capture(), any(SqlParameterSource.class), eq(Boolean.class));
        assertTrue(sql.getValue().contains("de.association_id=:associationId"));
        assertTrue(sql.getValue().contains("ce.association_id=:associationId"));
        assertFalse(sql.getValue().contains("m.state IN"));
        assertFalse(sql.getValue().contains("m.disabled_at IS NULL"));
        assertFalse(sql.getValue().contains("de.status='ACTIVE'"));
        assertFalse(sql.getValue().contains("m.deleted_at IS NULL"));
        assertFalse(sql.getValue().contains("d.deleted_at IS NULL"));
    }

    @Test
    void reopeningACompletedTaskCannotRetainOneHundredPercentProgress() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresCollaborationStore store = new PostgresCollaborationStore(jdbc, new ObjectMapper());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        store.transition(UUID.randomUUID(), 3, "OPEN", false,
                actor("ENTERPRISE_ADMIN", ENTERPRISE_ID));

        verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(sql.getValue().contains(
                "WHEN status='COMPLETED' AND :stage='OPEN' THEN LEAST(progress, 99)"));
    }

    private static ActorScope actor(String role, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + role, role.toLowerCase(),
                ASSOCIATION_ID, enterpriseId, Set.of(role), Set.of());
    }
}
