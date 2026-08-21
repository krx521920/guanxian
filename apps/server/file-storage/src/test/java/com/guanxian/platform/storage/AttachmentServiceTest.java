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
    private static final UUID ENTERPRISE_A = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(
                new InMemoryAttachmentMetadataStore(),
                new InMemoryObjectStorage(),
                new NoopAttachmentRateLimiter(),
                new StorageProperties());
    }

    @Test
    void uploadDownloadDeleteAndRestorePreserveContentAndVersions() {
        byte[] bytes = "%PDF-1.7\npolicy".getBytes(StandardCharsets.UTF_8);
        AttachmentView created = service.upload(
                enterpriseAdmin(ENTERPRISE_A), null, null, "PRIVATE",
                new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes));

        assertThat(created.status()).isEqualTo("ACTIVE");
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

    private static ActorScope enterpriseAdmin(UUID enterpriseId) {
        return actor("enterprise-admin", enterpriseId, Set.of("ENTERPRISE_ADMIN"));
    }

    private static ActorScope enterpriseMember(UUID enterpriseId) {
        return actor("enterprise-member", enterpriseId, Set.of("ENTERPRISE_MEMBER"));
    }

    private static ActorScope associationAdmin() {
        return actor("association-admin", null, Set.of("ASSOCIATION_ADMIN"));
    }

    private static ActorScope actor(String subject, UUID enterpriseId, Set<String> roles) {
        return new ActorScope(UUID.randomUUID(), subject, subject, ASSOCIATION, enterpriseId, roles, Set.of());
    }
}
