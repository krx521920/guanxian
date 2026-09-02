package com.guanxian.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresAttachmentContentValidationUpgradeIntegrationTest {
    private static final UUID PENDING_FILE =
            UUID.fromString("7a000000-0000-0000-0000-000000000001");
    private static final UUID VALIDATED_FILE =
            UUID.fromString("7a000000-0000-0000-0000-000000000002");
    private static final OffsetDateTime LEGACY_UPDATED_AT =
            OffsetDateTime.parse("2020-01-02T03:04:05Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Test
    void upgradesPendingFilesWithoutRewritingAlreadyValidatedFiles() {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        JdbcTemplate jdbc = jdbc();

        insertLegacyFile(jdbc, PENDING_FILE, "pending.txt", "PENDING", 4L);
        insertLegacyFile(jdbc, VALIDATED_FILE, "validated.txt", "VALIDATED", 7L);

        flyway(null).migrate();

        assertEquals("REQUIRES_REUPLOAD", scanStatus(jdbc, PENDING_FILE));
        assertEquals(5L, version(jdbc, PENDING_FILE));
        assertTrue(updatedAt(jdbc, PENDING_FILE).isAfter(LEGACY_UPDATED_AT));

        assertEquals("VALIDATED", scanStatus(jdbc, VALIDATED_FILE));
        assertEquals(7L, version(jdbc, VALIDATED_FILE));
        assertEquals(LEGACY_UPDATED_AT, updatedAt(jdbc, VALIDATED_FILE));
    }

    private void insertLegacyFile(
            JdbcTemplate jdbc,
            UUID id,
            String originalFilename,
            String scanStatus,
            long version) {
        jdbc.update("""
                INSERT INTO object_file
                  (id, bucket_name, object_key, original_filename, media_type,
                   size_bytes, sha256, scan_status, visibility, uploaded_by_subject,
                   uploaded_at, lifecycle_status, version, updated_at)
                VALUES (?, 'test-bucket', ?, ?, 'text/plain',
                        4, ?, ?, 'PRIVATE', 'migration-test-subject',
                        ?, 'ACTIVE', ?, ?)
                """,
                id,
                "migration/" + id,
                originalFilename,
                "0".repeat(64),
                scanStatus,
                LEGACY_UPDATED_AT,
                version,
                LEGACY_UPDATED_AT);
    }

    private String scanStatus(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject(
                "SELECT scan_status FROM object_file WHERE id=?", String.class, id);
    }

    private long version(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject(
                "SELECT version FROM object_file WHERE id=?", Long.class, id);
    }

    private OffsetDateTime updatedAt(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject(
                "SELECT updated_at FROM object_file WHERE id=?", OffsetDateTime.class, id);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
