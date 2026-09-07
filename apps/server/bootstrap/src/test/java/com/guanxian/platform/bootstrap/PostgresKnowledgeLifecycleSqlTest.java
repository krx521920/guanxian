package com.guanxian.platform.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.rag.KnowledgeRepository.LifecycleCommand;
import com.guanxian.platform.ai.rag.PostgresKnowledgeRepository;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresKnowledgeLifecycleSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"KNOWLEDGE_DELETE", "KNOWLEDGE_RESTORE", "KNOWLEDGE_SUBMIT"})
    void optionalLifecycleAssignmentsKeepWhereSeparateAndEveryParameterBound(String action) {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var repository = new PostgresKnowledgeRepository(jdbc, new ObjectMapper());
        var association = UUID.randomUUID();
        var command = new LifecycleCommand(UUID.randomUUID(), association, 7L, "DRAFT",
                action.equals("KNOWLEDGE_DELETE"), action.equals("KNOWLEDGE_RESTORE"), null,
                null, "test-owner", "test-owner", action, "sql-regression");
        // The stub updates zero rows, preserving the real optimistic-lock failure path.
        assertThatThrownBy(() -> repository.updateLifecycle(command)).isInstanceOf(PreconditionFailedException.class);
        var sql = ArgumentCaptor.forClass(String.class);
        var params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("\nWHERE id = :documentId AND association_id = :associationId")
                .contains("AND lifecycle_version = :expectedVersion")
                .doesNotContain("actorSubjectWHERE", "NULLWHERE");
        assertThat(params.getValue().getValue("associationId")).isEqualTo(association);
        assertThat(params.getValue().getValue("expectedVersion")).isEqualTo(7L);
        NamedParameterUtils.buildValueArray(NamedParameterUtils.parseSqlStatement(sql.getValue()), params.getValue(), null);
    }
}
