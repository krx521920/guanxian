package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryAttachmentMetadataStore implements AttachmentMetadataStore {
    private final ConcurrentMap<UUID, AttachmentView> entries = new ConcurrentHashMap<>();
    private final AttachmentEnterpriseScope enterpriseScope;

    InMemoryAttachmentMetadataStore(AttachmentEnterpriseScope enterpriseScope) {
        this.enterpriseScope = enterpriseScope;
    }

    @Override
    public AttachmentView create(AttachmentDraft draft, ActorScope actor) {
        requireCreateScope(draft, actor);
        Instant now = Instant.now();
        AttachmentView view = new AttachmentView(
                draft.id(), draft.associationId(), draft.enterpriseId(), draft.bucketName(), draft.objectKey(),
                draft.originalFilename(), draft.mediaType(), draft.sizeBytes(), draft.sha256(), draft.scanStatus(),
                draft.visibility(), "ACTIVE", 0, draft.uploadedBySubject(), now, now, null);
        if (entries.putIfAbsent(view.id(), view) != null) {
            throw new IllegalStateException("duplicate attachment id");
        }
        return view;
    }

    @Override
    public Optional<AttachmentView> findVisible(UUID id, ActorScope actor, boolean includeDeleted) {
        AttachmentView view = entries.get(id);
        return view != null && canRead(view, actor) && (includeDeleted || view.deletedAt() == null)
                ? Optional.of(view) : Optional.empty();
    }

    @Override
    public List<AttachmentView> listVisible(
            ActorScope actor, UUID enterpriseId, boolean includeDeleted, int offset, int limit) {
        return entries.values().stream()
                .filter(view -> enterpriseId == null || enterpriseId.equals(view.enterpriseId()))
                .filter(view -> includeDeleted || view.deletedAt() == null)
                .filter(view -> canRead(view, actor))
                .sorted(Comparator.comparing(AttachmentView::uploadedAt).reversed()
                        .thenComparing(AttachmentView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countVisible(ActorScope actor, UUID enterpriseId, boolean includeDeleted) {
        return entries.values().stream()
                .filter(view -> enterpriseId == null || enterpriseId.equals(view.enterpriseId()))
                .filter(view -> includeDeleted || view.deletedAt() == null)
                .filter(view -> canRead(view, actor))
                .count();
    }

    @Override
    public synchronized Optional<AttachmentView> softDelete(UUID id, long expectedVersion, ActorScope actor) {
        AttachmentView current = entries.get(id);
        if (current == null || current.deletedAt() != null || current.version() != expectedVersion
                || !canManage(current, actor)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        AttachmentView changed = copy(current, "DELETED", current.version() + 1, now, now);
        entries.put(id, changed);
        return Optional.of(changed);
    }

    @Override
    public synchronized Optional<AttachmentView> restore(UUID id, long expectedVersion, ActorScope actor) {
        AttachmentView current = entries.get(id);
        if (current == null || current.deletedAt() == null || current.version() != expectedVersion
                || !canManage(current, actor)) {
            return Optional.empty();
        }
        AttachmentView changed = copy(current, "ACTIVE", current.version() + 1, Instant.now(), null);
        entries.put(id, changed);
        return Optional.of(changed);
    }

    private static AttachmentView copy(
            AttachmentView value, String status, long version, Instant updatedAt, Instant deletedAt) {
        return new AttachmentView(
                value.id(), value.associationId(), value.enterpriseId(), value.bucketName(), value.objectKey(),
                value.originalFilename(), value.mediaType(), value.sizeBytes(), value.sha256(), value.scanStatus(),
                value.visibility(), status, version, value.uploadedBySubject(), value.uploadedAt(), updatedAt, deletedAt);
    }

    private static boolean canManage(AttachmentView value, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null
                    && actor.associationId().equals(value.associationId())
                    && (actor.enterpriseId() == null || actor.enterpriseId().equals(value.enterpriseId()));
        }
        if (actor.associationId() == null || !actor.associationId().equals(value.associationId())) {
            return false;
        }
        return actor.isAssociationStaff()
                || actor.isEnterpriseAdmin() && actor.enterpriseId() != null
                && actor.enterpriseId().equals(value.enterpriseId());
    }

    private static boolean canRead(AttachmentView value, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return (actor.associationId() == null || actor.associationId().equals(value.associationId()))
                    && (actor.enterpriseId() == null || actor.enterpriseId().equals(value.enterpriseId()));
        }
        if (actor.associationId() == null || !actor.associationId().equals(value.associationId())) {
            return false;
        }
        if (actor.isAssociationStaff() || "ASSOCIATION".equals(value.visibility())) {
            return true;
        }
        return actor.enterpriseId() != null && actor.enterpriseId().equals(value.enterpriseId());
    }

    private void requireCreateScope(AttachmentDraft draft, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null || !actor.associationId().equals(draft.associationId())
                    || !java.util.Objects.equals(actor.enterpriseId(), draft.enterpriseId())) {
                throw new ForbiddenException("ATTACHMENT_SCOPE_VIOLATION",
                        "attachment target is outside the selected system context");
            }
            requireEnterpriseAssociation(draft, actor);
            return;
        }
        if (actor.associationId() == null || !actor.associationId().equals(draft.associationId())
                || actor.isEnterpriseAdmin() && (actor.enterpriseId() == null
                || !actor.enterpriseId().equals(draft.enterpriseId()))) {
            throw new ForbiddenException("ATTACHMENT_SCOPE_VIOLATION",
                    "attachment target is outside the actor scope");
        }
        requireEnterpriseAssociation(draft, actor);
    }

    private void requireEnterpriseAssociation(AttachmentDraft draft, ActorScope actor) {
        if (draft.enterpriseId() != null && !enterpriseScope.contains(
                draft.associationId(), draft.enterpriseId(), actor)) {
            throw new ForbiddenException("ATTACHMENT_SCOPE_VIOLATION",
                    "target enterprise does not belong to the attachment association");
        }
    }
}
