package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.rag.KnowledgeDocumentParser;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import com.guanxian.platform.storage.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeControllerScopeTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private KnowledgeIngestionService ingestionService;
    private PolicyRagService ragService;
    private ActorScopeResolver actorScopeResolver;
    private Authentication authentication;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        ingestionService = mock(KnowledgeIngestionService.class);
        ragService = mock(PolicyRagService.class);
        actorScopeResolver = mock(ActorScopeResolver.class);
        authentication = mock(Authentication.class);
        controller = new KnowledgeController(
                ingestionService,
                ragService,
                actorScopeResolver,
                mock(KnowledgeDocumentParser.class),
                mock(AttachmentService.class));
        when(ragService.ask(any())).thenReturn(new PolicyRagService.RagAnswer(
                "暂无证据", List.of(), UUID.randomUUID(), "NO_EVIDENCE",
                "LEXICAL", 0, 0, BigDecimal.ZERO));
    }

    @Test
    void unscopedSystemAdministratorCanQueryGloballyButCannotIngest() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(null, null));

        controller.ask(new KnowledgeController.KnowledgeQuestionRequest(
                null, "全局知识查询", 3), authentication);

        ArgumentCaptor<PolicyRagService.RagQuestion> question =
                ArgumentCaptor.forClass(PolicyRagService.RagQuestion.class);
        verify(ragService).ask(question.capture());
        assertThat(question.getValue().associationId()).isNull();
        assertThat(question.getValue().privilegedKnowledgeAccess()).isTrue();

        assertThatThrownBy(() -> controller.ingest(textRequest(null), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSOCIATION_CONTEXT_REQUIRED"));
        verify(ingestionService, never()).ingest(any());
    }

    @Test
    void requestAssociationCannotOverrideSelectedOrMissingSystemContext() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(ASSOCIATION_A, null));

        assertThatThrownBy(() -> controller.ingest(textRequest(ASSOCIATION_B), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_CONTEXT_FORBIDDEN"));
        assertThatThrownBy(() -> controller.ask(new KnowledgeController.KnowledgeQuestionRequest(
                ASSOCIATION_B, "跨协会查询", 3), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_CONTEXT_FORBIDDEN"));

        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(null, null));
        assertThatThrownBy(() -> controller.ask(new KnowledgeController.KnowledgeQuestionRequest(
                ASSOCIATION_A, "用请求体建立范围", 3), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_CONTEXT_FORBIDDEN"));
    }

    @Test
    void matchingRequestAssociationRemainsCompatibleButHeaderContextIsAuthoritative() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(ASSOCIATION_A, null));

        controller.ingest(textRequest(ASSOCIATION_A), authentication);
        controller.ask(new KnowledgeController.KnowledgeQuestionRequest(
                ASSOCIATION_A, "本协会查询", 3), authentication);

        ArgumentCaptor<KnowledgeIngestionService.KnowledgeTextDocument> document =
                ArgumentCaptor.forClass(KnowledgeIngestionService.KnowledgeTextDocument.class);
        verify(ingestionService).ingest(document.capture());
        assertThat(document.getValue().associationId()).isEqualTo(ASSOCIATION_A);
        ArgumentCaptor<PolicyRagService.RagQuestion> question =
                ArgumentCaptor.forClass(PolicyRagService.RagQuestion.class);
        verify(ragService).ask(question.capture());
        assertThat(question.getValue().associationId()).isEqualTo(ASSOCIATION_A);
    }

    private static KnowledgeController.KnowledgeDocumentRequest textRequest(UUID associationId) {
        return new KnowledgeController.KnowledgeDocumentRequest(
                null, associationId, "范围测试文档", "POLICY", "MANUAL_TEXT",
                null, "ASSOCIATION", "PUBLISHED", "知识写入必须服从已选系统上下文。" );
    }

    private static ActorScope systemAdmin(UUID associationId, UUID enterpriseId) {
        return new ActorScope(null, "system-subject", "system-admin",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
