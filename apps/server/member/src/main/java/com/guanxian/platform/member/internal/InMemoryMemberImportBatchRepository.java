package com.guanxian.platform.member.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "memory")
class InMemoryMemberImportBatchRepository implements MemberImportBatchRepository {
    private final ConcurrentMap<UUID, MemberImportBatch> batches = new ConcurrentHashMap<>();

    @Override
    public void save(MemberImportBatch batch) {
        batches.put(batch.id(), batch);
    }

    @Override
    public Optional<MemberImportBatch> findById(UUID id) {
        return Optional.ofNullable(batches.get(id));
    }

    @Override
    public Optional<MemberImportBatch> findByIdForCommit(UUID id) {
        return findById(id);
    }

    @Override
    public synchronized boolean markCommitted(UUID id, Map<Integer, UUID> importedRows) {
        MemberImportBatch batch = batches.get(id);
        if (batch == null || !"PREVIEWED".equals(batch.status())) {
            return false;
        }
        var rows = batch.rows().stream().map(row -> {
            UUID enterpriseId = importedRows.get(row.rowNumber());
            return enterpriseId == null ? row : new MemberImportRow(
                    row.rowNumber(), row.data(), row.errors(), "IMPORTED", enterpriseId);
        }).toList();
        batches.put(id, new MemberImportBatch(
                batch.id(), batch.associationId(), batch.originalFilename(), "COMMITTED",
                batch.createdBySubject(), batch.createdAt(), Instant.now(), rows));
        return true;
    }
}
