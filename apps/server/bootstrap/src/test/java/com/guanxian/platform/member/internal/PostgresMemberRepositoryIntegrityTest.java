package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresMemberRepositoryIntegrityTest {

    @Test
    void uniqueViolationIsReportedAsMemberConflict() {
        NamedParameterJdbcTemplate jdbc = failingJdbc("23505");
        PostgresMemberRepository repository = repository(jdbc);

        assertThrows(ConflictException.class, () -> repository.insert(member()));
    }

    @Test
    void nonUniqueIntegrityViolationIsNotMisreportedAsMemberConflict() {
        DataIntegrityViolationException violation = violation("23503");
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(violation);
        PostgresMemberRepository repository = repository(jdbc);

        DataIntegrityViolationException actual = assertThrows(
                DataIntegrityViolationException.class, () -> repository.insert(member()));

        assertSame(violation, actual);
    }

    private static NamedParameterJdbcTemplate failingJdbc(String sqlState) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(violation(sqlState));
        return jdbc;
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("database constraint", new SQLException("constraint", sqlState));
    }

    private static PostgresMemberRepository repository(NamedParameterJdbcTemplate jdbc) {
        return new PostgresMemberRepository(jdbc, new ObjectMapper(), "北京地下管线协会");
    }

    private static MemberProfile member() {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return new MemberProfile(
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000106"),
                "约束验收企业", "91110000TEST23505X", "技术服务单位", null, null, null, null,
                List.of(), List.of(), List.of(), "MEMBERS", "ACTIVE", 0, now, now, null, null, null);
    }
}
