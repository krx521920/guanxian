package com.guanxian.platform.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PostgresAttachmentMetadataStoreTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void rejectsCrossAssociationEnterpriseBeforeExecutingInsert() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PostgresAttachmentMetadataStore store = new PostgresAttachmentMetadataStore(
                jdbc, new ObjectMapper(), (associationId, enterpriseId, actor) -> false);
        AttachmentDraft draft = new AttachmentDraft(
                UUID.randomUUID(), ASSOCIATION_A, ENTERPRISE_B, "bucket", "object/key",
                "cross.txt", "text/plain", 5, "0".repeat(64), "PRIVATE", "association-admin");
        ActorScope actor = new ActorScope(
                UUID.randomUUID(), "association-admin", "association-admin",
                ASSOCIATION_A, null, Set.of("ASSOCIATION_ADMIN"), Set.of());

        assertThatThrownBy(() -> store.create(draft, actor))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ATTACHMENT_SCOPE_VIOLATION"));
        verifyNoInteractions(jdbc);
    }
}
