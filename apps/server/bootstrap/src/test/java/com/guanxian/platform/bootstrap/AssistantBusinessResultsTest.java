package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.*;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.ecosystem.*;
import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.error.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AssistantBusinessResultsTest {
    final MemberService members = mock(MemberService.class);
    final AssistantBusinessQueryTools tools = new AssistantBusinessQueryTools(members,
            mock(EcosystemCatalogService.class), mock(EcosystemMatchService.class), mock(CollaborationService.class));
    final ActorScope actor = new ActorScope(null, "fixture-actor", "fixture", UUID.randomUUID(), null,
            Set.of("ASSOCIATION_OPERATOR"), Set.of());
    final AssistantBusinessResults journal = new AssistantBusinessResults();
    final ToolContext context = context(actor, Set.of("MEMBER_READ"), journal);

    @Test void searchReceiptUsesActualCountFiltersAndSanitizedStableIds() {
        var profiles = IntStream.range(0, 12).mapToObj(i -> member(UUID.randomUUID())).toList();
        when(members.findAll("监测", null, false, actor)).thenReturn(profiles);
        assertThat(tools.searchMemberEnterprises("  监测  ", context).status()).isEqualTo("OK");
        var result = journal.snapshot().getFirst();
        assertThat(result.total()).isEqualTo(12);
        assertThat(result.items()).hasSize(10);
        assertThat(result.filters()).containsEntry("关键词", "监测");
        assertThat(result.associationId()).isEqualTo(actor.associationId());
        assertThat(result.queriedAt()).isAfter(Instant.EPOCH);
        assertThat(result.items().getFirst().id()).isEqualTo(profiles.getFirst().id());
        assertThat(result.toString()).doesNotContain("SECRET", "13800000000", "fixture@example.cn");
        assertThat(result.items().getFirst().fields()).containsOnlyKeys("category", "capabilities", "products", "services", "status", "updatedAt");
    }

    @Test void permissionDenialAndBackendFailureAreNotReportedAsAnEmptySuccess() {
        tools.searchMemberEnterprises(null, context(actor, Set.of(), journal));
        assertThat(journal.snapshot().getFirst().status()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(members);
        when(members.findAll(null, null, false, actor)).thenThrow(new IllegalStateException("SECRET database error"));
        tools.searchMemberEnterprises(null, context);
        assertThat(journal.snapshot().getLast().status()).isEqualTo("FAILED");
        assertThat(journal.snapshot().getLast().toString()).doesNotContain("SECRET");
    }

    @Test void allAssociationLocalQueryUsesVerifiedGlobalActorAndLabelsItsReceipt() {
        var global = new ActorScope(null, "system", "system", null, null, Set.of("SYSTEM_ADMIN"), Set.of());
        when(members.findAll(null, null, false, global)).thenReturn(List.of(member(UUID.randomUUID())));
        var local = tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(
                new AssistantAccessContext(global, Set.of("MEMBER_READ")),
                "查询全部协会的会员企业", "协会工作台", "/dashboard")).orElseThrow();
        assertThat(local.businessResults()).hasSize(1);
        var receipt = local.businessResults().getFirst();
        assertThat(receipt.associationId()).isNull();
        assertThat(receipt.scope()).contains("全部协会");
        assertThat(receipt.total()).isEqualTo(1);
        verify(members).findAll(null, null, false, global);
        var denied = new AssistantBusinessResults();
        assertThat(tools.searchMemberEnterprises(null, context(global, Set.of(), denied)).status()).isEqualTo("FORBIDDEN");
        verifyNoMoreInteractions(members);
    }

    @Test void sayingAllAssociationsDoesNotExpandAnOrdinaryAccountsScope() {
        when(members.findAll(null, null, false, actor)).thenReturn(List.of());
        var local = tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(
                new AssistantAccessContext(actor, Set.of("MEMBER_READ")),
                "查询所有协会的会员企业", "会员企业", "/members")).orElseThrow();
        assertThat(local.businessResults().getFirst().associationId()).isEqualTo(actor.associationId());
        assertThat(local.businessResults().getFirst().scope()).doesNotContain("全部协会");
        verify(members).findAll(null, null, false, actor);
    }

    @Test void comparisonRechecksEveryIdAndUsesCurrentFields() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(members.get(first, actor)).thenReturn(member(first));
        when(members.get(second, actor)).thenReturn(member(second));
        assertThat(tools.compareMemberEnterprises(List.of(first, second), context).status()).isEqualTo("OK");
        assertThat(journal.snapshot().getFirst().items()).extracting(AssistantBusinessResults.Item::id).containsExactly(first, second);
        verify(members).get(first, actor); verify(members).get(second, actor); verifyNoMoreInteractions(members);
    }

    @Test void duplicateTooFewOrTooManySelectionsNeverQuery() {
        UUID id = UUID.randomUUID();
        for (var ids : List.of(List.of(id), List.of(id, id), IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList())) {
            assertThat(tools.compareMemberEnterprises(ids, context).status()).isEqualTo("INVALID");
        }
        verifyNoInteractions(members);
    }

    @Test void inaccessibleSecondEnterpriseDoesNotLeakTheFirstPartialResult() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(members.get(first, actor)).thenReturn(member(first));
        when(members.get(second, actor)).thenThrow(new ForbiddenException("FORBIDDEN", "secret cross association record"));
        tools.compareMemberEnterprises(List.of(first, second), context);
        assertThat(journal.snapshot().getFirst().status()).isEqualTo("FORBIDDEN");
        assertThat(journal.snapshot().getFirst().items()).isEmpty();
        assertThat(journal.snapshot().toString()).doesNotContain("secret", first.toString());
    }

    @Test void recommendationsKeepConditionsSeparateFromEvidenceAndNeverWriteMatches() {
        UUID id = UUID.randomUUID();
        when(members.get(id, actor)).thenReturn(member(id));
        var criteria = List.of(new AssistantBusinessQueryTools.FitCriterion("capabilities", "管线监测"),
                new AssistantBusinessQueryTools.FitCriterion("services", "应急抢修"),
                new AssistantBusinessQueryTools.FitCriterion("category", "设备制造"));
        assertThat(tools.explainMemberFit(List.of(id), criteria, context).status()).isEqualTo("OK");
        var item = journal.snapshot().getFirst().items().getFirst();
        assertThat(item.evidence()).extracting(AssistantBusinessResults.Evidence::state).containsExactly("MATCHED", "INSUFFICIENT", "UNMET");
        assertThat(item.evidence()).extracting(AssistantBusinessResults.Evidence::criterion).containsExactly("管线监测", "应急抢修", "设备制造");
        verify(members).get(id, actor); verifyNoMoreInteractions(members);
    }

    @Test void missingOrNegatedFreeTextDoesNotAssertMissingCapabilityOrAPositiveMatch() {
        var criterion = new AssistantBusinessQueryTools.FitCriterion("capabilities", "管线监测");
        assertThat(AssistantBusinessQueryTools.evaluate(criterion, Map.of()).state()).isEqualTo("INSUFFICIENT");
        assertThat(AssistantBusinessQueryTools.evaluate(criterion, Map.of("capabilities", "暂不提供管线监测")).state()).isEqualTo("INSUFFICIENT");
    }

    @Test void localModeAlsoReturnsReceiptsAndRequestJournalsRemainIsolated() {
        when(members.findAll(null, null, false, actor)).thenReturn(List.of(member(UUID.randomUUID())));
        var local = tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(
                new AssistantAccessContext(actor, Set.of("MEMBER_READ")), "查询会员企业", "会员企业", "/members"));
        assertThat(local).isPresent();
        assertThat(local.orElseThrow().businessResults()).hasSize(1);
        assertThat(journal.snapshot()).isEmpty();
        var other = new AssistantBusinessResults();
        tools.searchMemberEnterprises(null, context(actor, Set.of(), other));
        assertThat(other.snapshot()).hasSize(1);
        assertThat(journal.snapshot()).isEmpty();
    }

    private static ToolContext context(ActorScope actor, Set<String> authorities, AssistantBusinessResults journal) {
        return new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY, new AssistantAccessContext(actor, authorities),
                AssistantBusinessResults.CONTEXT_KEY, journal));
    }
    private MemberProfile member(UUID id) {
        return new MemberProfile(id, actor.associationId(), "虚构监测企业", "SECRET", "技术服务", "虚构地址",
                "SECRET", "13800000000", "fixture@example.cn", "简介", List.of("管线监测"), List.of("平台"),
                List.of(), List.of(), List.of(), "MEMBERS", "ACTIVE", 1, Instant.EPOCH, Instant.EPOCH, null, null, null);
    }
}
