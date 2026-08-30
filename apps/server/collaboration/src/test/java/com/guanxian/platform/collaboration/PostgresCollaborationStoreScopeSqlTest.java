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
        assertTrue(sql.getValue().contains("oe.status='ACTIVE'"));
        assertTrue(sql.getValue().contains("lde.status='ACTIVE'"));

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

    private static ActorScope actor(String role, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + role, role.toLowerCase(),
                ASSOCIATION_ID, enterpriseId, Set.of(role), Set.of());
    }
}
