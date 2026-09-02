package com.guanxian.platform.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresNotificationStoreTest {
    @Test
    void messageFilterClauseKeepsListAndCountQueriesServerScoped() {
        assertEquals("", PostgresNotificationStore.messageFilterClause(false, null));
        assertEquals(" AND status = :status",
                PostgresNotificationStore.messageFilterClause(false, "ARCHIVED"));
        assertEquals(" AND read_at IS NULL AND status <> 'ARCHIVED'",
                PostgresNotificationStore.messageFilterClause(true, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void markReadSqlCannotUpdateArchivedMessages() {
        assertEquals(" AND status <> 'ARCHIVED'",
                PostgresNotificationStore.markReadStatusClause());

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(anyString(), any(MapSqlParameterSource.class),
                any(RowMapper.class));
        PostgresNotificationStore store = new PostgresNotificationStore(jdbc, new ObjectMapper());

        store.markRead(UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("AND status <> 'ARCHIVED'"));
        assertTrue(sql.getValue().indexOf("status <> 'ARCHIVED'")
                < sql.getValue().indexOf("RETURNING"));
    }
}
