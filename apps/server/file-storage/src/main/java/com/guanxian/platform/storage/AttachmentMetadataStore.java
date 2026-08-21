package com.guanxian.platform.storage;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AttachmentMetadataStore {
    AttachmentView create(AttachmentDraft draft, ActorScope actor);

    Optional<AttachmentView> findVisible(UUID id, ActorScope actor, boolean includeDeleted);

    List<AttachmentView> listVisible(ActorScope actor, UUID enterpriseId, boolean includeDeleted, int offset, int limit);

    long countVisible(ActorScope actor, UUID enterpriseId, boolean includeDeleted);

    Optional<AttachmentView> softDelete(UUID id, long expectedVersion, ActorScope actor);

    Optional<AttachmentView> restore(UUID id, long expectedVersion, ActorScope actor);
}
