package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentServiceTest {
    private static final UUID ASSOCIATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ENTERPRISE_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE_B = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ENTERPRISE_C = UUID.fromString("20000000-0000-0000-0000-000000000003");

    private AttachmentService service;
    private InMemoryAttachmentMetadataStore metadata;

    @BeforeEach
    void setUp() {
        AttachmentEnterpriseScope enterpriseScope = (associationId, enterpriseId, actor) ->
                (ASSOCIATION.equals(associationId)
                        && Set.of(ENTERPRISE_A, ENTERPRISE_B).contains(enterpriseId))
                        || (ASSOCIATION_B.equals(associationId) && ENTERPRISE_C.equals(enterpriseId));
        metadata = new InMemoryAttachmentMetadataStore(enterpriseScope);
        service = new AttachmentService(
                metadata,
                new InMemoryObjectStorage(),
                new NoopAttachmentRateLimiter(),
                new StorageProperties(),
                enterpriseScope);
    }

    @Test
    void uploadDownloadDeleteAndRestorePreserveContentAndVersions() {
        byte[] bytes = "%PDF-1.7\npolicy".getBytes(StandardCharsets.UTF_8);
        AttachmentView created = service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, null, "PRIVATE",
                new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes));

        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.scanStatus()).isEqualTo("VALIDATED");
        assertThat(created.version()).isZero();
        assertThat(service.download(created.id(), enterpriseAdmin(ENTERPRISE_A)).content())
                .containsExactly(bytes);

        AttachmentView deleted = service.delete(created.id(), 0, enterpriseAdmin(ENTERPRISE_A));
        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(deleted.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.download(created.id(), enterpriseAdmin(ENTERPRISE_A)))
                .isInstanceOf(NotFoundException.class);

        AttachmentView restored = service.restore(created.id(), 1, enterpriseAdmin(ENTERPRISE_A));
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(restored.version()).isEqualTo(2);
        assertThat(service.download(created.id(), enterpriseAdmin(ENTERPRISE_A)).content())
                .containsExactly(bytes);
    }

    @Test
    void rejectsFilenameTraversalAndContentTypeSpoofing() {
        assertThatThrownBy(() -> service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, null, "PRIVATE",
                new MockMultipartFile("file", "../policy.pdf", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("filename");

        assertThatThrownBy(() -> service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, null, "PRIVATE",
                new MockMultipartFile("file", "policy.pdf", "application/pdf",
                        "not a pdf".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("content");
    }

    @Test
    void enterpriseAdministratorCannotTargetOrReadAnotherEnterprise() {
        assertThatThrownBy(() -> service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, ENTERPRISE_B, "PRIVATE",
                new MockMultipartFile("file", "note.txt", "text/plain",
                        "safe".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ForbiddenException.class);

        AttachmentView other = service.upload(
                associationAdmin(), null, ENTERPRISE_B, "PRIVATE",
                new MockMultipartFile("file", "note.txt", "text/plain",
                        "safe".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> service.get(other.id(), enterpriseAdmin(ENTERPRISE_A), false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void associationAdministratorCannotAttachAnotherAssociationsEnterprise() {
        assertThatThrownBy(() -> service.upload(
                associationAdmin(), null, ENTERPRISE_C, "PRIVATE", textFile("cross.txt", "cross")))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ATTACHMENT_SCOPE_VIOLATION"));

        AttachmentDraft crossAssociationDraft = new AttachmentDraft(
                UUID.randomUUID(), ASSOCIATION, ENTERPRISE_C, "test", "cross/object",
                "cross.txt", "text/plain", 5, "0".repeat(64), "VALIDATED", "PRIVATE", "association-admin");
        assertThatThrownBy(() -> metadata.create(crossAssociationDraft, associationAdmin()))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ATTACHMENT_SCOPE_VIOLATION"));
    }

    @Test
    void associationVisibilityIsReadableAcrossMemberEnterprises() {
        AttachmentView shared = service.upload(
                associationAdmin(), null, ENTERPRISE_A, "ASSOCIATION",
                new MockMultipartFile("file", "notice.txt", "text/plain",
                        "notice".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.get(shared.id(), enterpriseMember(ENTERPRISE_B), false).id())
                .isEqualTo(shared.id());
    }

    @Test
    void staleVersionIsRejected() {
        AttachmentView created = service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, null, "PRIVATE",
                new MockMultipartFile("file", "note.txt", "text/plain",
                        "safe".getBytes(StandardCharsets.UTF_8)));
        service.delete(created.id(), 0, enterpriseAdmin(ENTERPRISE_A));

        assertThatThrownBy(() -> service.restore(created.id(), 0, enterpriseAdmin(ENTERPRISE_A)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void pendingOrLegacyAttachmentContentCannotBeDownloaded() {
        AttachmentDraft pending = new AttachmentDraft(
                UUID.randomUUID(), ASSOCIATION, ENTERPRISE_A, "test", "pending/object",
                "pending.txt", "text/plain", 4, "0".repeat(64), "REQUIRES_REUPLOAD",
                "PRIVATE", "association-admin");
        AttachmentView created = metadata.create(pending, associationAdmin());

        assertThatThrownBy(() -> service.download(created.id(), associationAdmin()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ATTACHMENT_CONTENT_UNAVAILABLE"));
    }

    @Test
    void systemAdministratorCannotEstablishWriteScopeFromRequestParameters() {
        MockMultipartFile file = textFile("context.txt", "context");

        assertThatThrownBy(() -> service.upload(
                systemAdmin(null, null), ASSOCIATION, ENTERPRISE_A, "PRIVATE", file))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSOCIATION_CONTEXT_REQUIRED"));

        assertThatThrownBy(() -> service.upload(
                systemAdmin(ASSOCIATION, null), ASSOCIATION_B, null, "ASSOCIATION", file))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSOCIATION_SCOPE_VIOLATION"));

        assertThatThrownBy(() -> service.upload(
                systemAdmin(ASSOCIATION, null), ASSOCIATION, ENTERPRISE_A, "PRIVATE", file))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ENTERPRISE_SCOPE_VIOLATION"));
    }

    @Test
    void systemAdministratorReadsGloballyButSelectedContextsStrictlyNarrowEveryAttachmentOperation() {
        AttachmentView enterpriseA = service.upload(
                systemAdmin(ASSOCIATION, ENTERPRISE_A), ASSOCIATION, ENTERPRISE_A, "PRIVATE",
                textFile("enterprise-a.txt", "enterprise a"));
        AttachmentView associationA = service.upload(
                systemAdmin(ASSOCIATION, null), ASSOCIATION, null, "ASSOCIATION",
                textFile("association-a.txt", "association a"));
        AttachmentView associationB = service.upload(
                systemAdmin(ASSOCIATION_B, null), ASSOCIATION_B, null, "ASSOCIATION",
                textFile("association-b.txt", "association b"));

        assertThat(service.page(systemAdmin(null, null), null, false, 0, 20).total()).isEqualTo(3);
        assertThat(service.page(systemAdmin(ASSOCIATION, null), null, false, 0, 20).total()).isEqualTo(2);
        assertThat(service.page(systemAdmin(ASSOCIATION, ENTERPRISE_A), null, false, 0, 20).items())
                .extracting(AttachmentView::id)
                .containsExactly(enterpriseA.id());
        assertThat(service.get(associationB.id(), systemAdmin(null, null), false).id())
                .isEqualTo(associationB.id());

        assertThatThrownBy(() -> service.page(
                systemAdmin(ASSOCIATION, ENTERPRISE_A), ENTERPRISE_B, false, 0, 20))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ENTERPRISE_SCOPE_VIOLATION"));
        assertThatThrownBy(() -> service.get(
                associationB.id(), systemAdmin(ASSOCIATION, null), false))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.download(
                associationA.id(), systemAdmin(ASSOCIATION, ENTERPRISE_A)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(
                enterpriseA.id(), enterpriseA.version(), systemAdmin(null, null)))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSOCIATION_CONTEXT_REQUIRED"));
        assertThatThrownBy(() -> service.delete(
                associationB.id(), associationB.version(), systemAdmin(ASSOCIATION, null)))
                .isInstanceOf(NotFoundException.class);

        AttachmentView deleted = service.delete(
                enterpriseA.id(), enterpriseA.version(), systemAdmin(ASSOCIATION, ENTERPRISE_A));
        AttachmentView restored = service.restore(
                deleted.id(), deleted.version(), systemAdmin(ASSOCIATION, ENTERPRISE_A));
        assertThat(restored.status()).isEqualTo("ACTIVE");
        assertThat(restored.version()).isEqualTo(2);
    }

    private static ActorScope enterpriseAdmin(UUID enterpriseId) {
        return actor("enterprise-admin", enterpriseId, Set.of("ENTERPRISE_ADMIN"));
    }

    private static ActorScope enterpriseMember(UUID enterpriseId) {
        return actor("enterprise-member", enterpriseId, Set.of("ENTERPRISE_MEMBER"));
    }

    private static ActorScope associationAdmin() {
        return actor("association-admin", null, Set.of("ASSOCIATION_ADMIN"));
    }

    private static ActorScope systemAdmin(UUID associationId, UUID enterpriseId) {
        return new ActorScope(UUID.randomUUID(), "system-admin", "system-admin",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }

    private static MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile(
                "file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static ActorScope actor(String subject, UUID enterpriseId, Set<String> roles) {
        return new ActorScope(UUID.randomUUID(), subject, subject, ASSOCIATION, enterpriseId, roles, Set.of());
    }
}
