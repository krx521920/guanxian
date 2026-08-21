package com.guanxian.platform.member.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

interface MemberImportBatchRepository {
    void save(MemberImportBatch batch);

    Optional<MemberImportBatch> findById(UUID id);

    Optional<MemberImportBatch> findByIdForCommit(UUID id);

    boolean markCommitted(UUID id, Map<Integer, UUID> importedRows);
}
