package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresMemberImportBatchRepository implements MemberImportBatchRepository {
    private static final TypeReference<List<String>> ERRORS = new TypeReference<>() {
    };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PostgresMemberImportBatchRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(MemberImportBatch batch) {
        jdbc.update("""
                INSERT INTO member_import_batch (
                    id, association_id, original_filename, status, total_rows,
                    valid_rows, invalid_rows, created_by_subject, created_at)
                VALUES (:id, :associationId, :filename, 'PREVIEWED', :totalRows,
                        :validRows, :invalidRows, :subject, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", batch.id())
                .addValue("associationId", batch.associationId())
                .addValue("filename", batch.originalFilename())
                .addValue("totalRows", batch.rows().size())
                .addValue("validRows", batch.validRows())
                .addValue("invalidRows", batch.invalidRows())
                .addValue("subject", batch.createdBySubject())
                .addValue("createdAt", Timestamp.from(batch.createdAt())));
        for (MemberImportRow row : batch.rows()) {
            jdbc.update("""
                    INSERT INTO member_import_row (batch_id, row_number, payload, errors, status)
                    VALUES (:batchId, :rowNumber, CAST(:payload AS jsonb), CAST(:errors AS jsonb), :status)
                    """, new MapSqlParameterSource()
                    .addValue("batchId", batch.id())
                    .addValue("rowNumber", row.rowNumber())
                    .addValue("payload", writeJson(row.data()))
                    .addValue("errors", writeJson(row.errors()))
                    .addValue("status", row.status()));
        }
    }

    @Override
    public Optional<MemberImportBatch> findById(UUID id) {
        return find(id, false);
    }

    @Override
    public Optional<MemberImportBatch> findByIdForCommit(UUID id) {
        return find(id, true);
    }

    private Optional<MemberImportBatch> find(UUID id, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<MemberImportBatch> batches = jdbc.query("""
                SELECT id, association_id, original_filename, status, created_by_subject, created_at, committed_at
                FROM member_import_batch WHERE id = :id
                """ + lock, new MapSqlParameterSource("id", id), (rs, row) -> new MemberImportBatch(
                rs.getObject("id", UUID.class), rs.getObject("association_id", UUID.class),
                rs.getString("original_filename"), rs.getString("status"), rs.getString("created_by_subject"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant(),
                List.of()));
        if (batches.isEmpty()) {
            return Optional.empty();
        }
        MemberImportBatch batch = batches.getFirst();
        List<MemberImportRow> rows = jdbc.query("""
                SELECT row_number, payload, errors, status, enterprise_id
                FROM member_import_row WHERE batch_id = :batchId ORDER BY row_number
                """, new MapSqlParameterSource("batchId", id), (rs, row) -> new MemberImportRow(
                rs.getInt("row_number"), readRequest(rs.getString("payload")),
                readErrors(rs.getString("errors")), rs.getString("status"),
                rs.getObject("enterprise_id", UUID.class)));
        return Optional.of(new MemberImportBatch(
                batch.id(), batch.associationId(), batch.originalFilename(), batch.status(),
                batch.createdBySubject(), batch.createdAt(), batch.committedAt(), rows));
    }

    @Override
    public boolean markCommitted(UUID id, Map<Integer, UUID> importedRows) {
        for (Map.Entry<Integer, UUID> imported : importedRows.entrySet()) {
            jdbc.update("""
                    UPDATE member_import_row SET status = 'IMPORTED', enterprise_id = :enterpriseId
                    WHERE batch_id = :batchId AND row_number = :rowNumber AND status = 'VALID'
                    """, new MapSqlParameterSource()
                    .addValue("batchId", id)
                    .addValue("rowNumber", imported.getKey())
                    .addValue("enterpriseId", imported.getValue()));
        }
        return jdbc.update("""
                UPDATE member_import_batch SET status = 'COMMITTED', committed_at = now()
                WHERE id = :id AND status = 'PREVIEWED'
                """, new MapSqlParameterSource("id", id)) == 1;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("member import data could not be serialized", exception);
        }
    }

    private MemberUpsertRequest readRequest(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, MemberUpsertRequest.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored member import payload is invalid JSON", exception);
        }
    }

    private List<String> readErrors(String json) throws SQLException {
        try {
            return List.copyOf(objectMapper.readValue(json, ERRORS));
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored member import errors are invalid JSON", exception);
        }
    }
}
