package com.guanxian.platform.policy;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyServiceTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ASSOCIATION_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final PolicyService service = new PolicyService(new InMemoryPolicyStore());

    @Test
    void completesReviewLifecycleAndKeepsHistory() {
        ActorScope admin = associationAdmin(ASSOCIATION_A);
        PolicyView created = service.create(request("专项政策", "MEMBERS"), admin);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.version()).isZero();

        PolicyView submitted = service.submit(UUID.fromString(created.id()), 0, admin);
        PolicyView published = service.review(UUID.fromString(created.id()), 1,
                new PolicyReviewRequest(true, "出处已核验"), admin);

        assertThat(submitted.status()).isEqualTo("PENDING_REVIEW");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.version()).isEqualTo(2);
        assertThat(service.history(UUID.fromString(created.id()), admin, 20))
                .extracting(PolicyHistoryView::action)
                .containsExactly("APPROVE", "SUBMIT", "CREATE");
    }

    @Test
    void enforcesAssociationAndPartnerVisibility() {
        ActorScope admin = associationAdmin(ASSOCIATION_A);
        PolicyView policy = service.create(request("跨协会标准", "PARTNERS"), admin);
        service.submit(UUID.fromString(policy.id()), 0, admin);
        service.review(UUID.fromString(policy.id()), 1, new PolicyReviewRequest(true, null), admin);

        ActorScope unrelated = actor(ASSOCIATION_B, Set.of("ENTERPRISE_ADMIN"), Set.of());
        ActorScope partner = actor(ASSOCIATION_B, Set.of("ENTERPRISE_ADMIN"), Set.of(ASSOCIATION_A));

        assertThat(service.findAll("跨协会标准", unrelated)).isEmpty();
        assertThat(service.findAll("跨协会标准", partner)).hasSize(1);
    }

    @Test
    void rejectsEnterprisePolicyWritesAndStaleVersions() {
        assertThatThrownBy(() -> service.create(request("越权", "MEMBERS"),
                actor(ASSOCIATION_A, Set.of("ENTERPRISE_ADMIN"), Set.of())))
                .isInstanceOf(ForbiddenException.class);

        ActorScope admin = associationAdmin(ASSOCIATION_A);
        PolicyView policy = service.create(request("并发政策", "MEMBERS"), admin);
        service.submit(UUID.fromString(policy.id()), 0, admin);
        assertThatThrownBy(() -> service.disable(UUID.fromString(policy.id()), 0, admin))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void softDeleteAndRestoreRequireLatestVersion() {
        ActorScope admin = associationAdmin(ASSOCIATION_A);
        PolicyView created = service.create(request("可恢复政策", "MEMBERS"), admin);
        PolicyView deleted = service.delete(UUID.fromString(created.id()), 0, admin);

        assertThat(deleted.deleted()).isTrue();
        assertThat(service.page(admin, "可恢复政策", false, 0, 20).items()).isEmpty();
        PolicyView restored = service.restore(UUID.fromString(created.id()), 1, admin);
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.status()).isEqualTo("DRAFT");
        assertThat(restored.version()).isEqualTo(2);
    }

    private static PolicyUpsertRequest request(String title, String visibility) {
        return new PolicyUpsertRequest(null, title, "北京地下管线协会", "京管协〔2026〕1号",
                "行业协会", "信息管理", LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 21), "https://example.test/policy", "经核验的摘要",
                List.of("地下管线", "信息管理"), visibility);
    }

    private static ActorScope associationAdmin(UUID associationId) {
        return actor(associationId, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }

    private static ActorScope actor(UUID associationId, Set<String> roles, Set<UUID> partners) {
        return new ActorScope(UUID.randomUUID(), "test-subject", "tester",
                associationId, null, roles, partners);
    }
}
