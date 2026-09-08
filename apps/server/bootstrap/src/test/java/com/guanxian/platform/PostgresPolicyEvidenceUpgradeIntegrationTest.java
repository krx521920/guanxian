package com.guanxian.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises both prior V30 and the deployed V30.1 with existing source-directory records. */
@EnabledIf("com.guanxian.platform.IsolatedPolicyPostgres#available")
class PostgresPolicyEvidenceUpgradeIntegrationTest {
    private final IsolatedPolicyPostgres POSTGRES = new IsolatedPolicyPostgres();

    @AfterEach void stopPostgres() { POSTGRES.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"30", "30.1"})
    void upgradePreservesLegacyRecordsAndEnforcesSummaryEvidenceBoundary(String previousVersion) {
        Map<String, Supplier<Object>> properties = new HashMap<>();
        POSTGRES.register(properties::put);
        var dataSource = new DriverManagerDataSource(
                (String) properties.get("spring.datasource.url").get(),
                (String) properties.get("spring.datasource.username").get(),
                (String) properties.get("spring.datasource.password").get());
        Flyway.configure().dataSource(dataSource).target(previousVersion).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID association = UUID.randomUUID(), policy = UUID.randomUUID(), summaryEnterprise = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id,name) VALUES (?,'V31升级测试协会')", association);
        jdbc.update("""
                INSERT INTO policy_document(id,association_id,title,summary,status)
                VALUES (?,?,'V30存量政策','供水监测摘要','PUBLISHED')
                """, policy, association);
        for (String status : List.of("PENDING_REVIEW", "APPROVED", "REJECTED")) {
            UUID enterprise = UUID.randomUUID();
            jdbc.update("INSERT INTO enterprise(id,association_id,name,status) VALUES (?,?,?,'ACTIVE')",
                    enterprise, association, "V30存量企业-" + status);
            jdbc.update("""
                    INSERT INTO policy_impact_analysis(policy_document_id,enterprise_id,impact_level,summary,
                        evidence_chunk_ids,status,reviewed_by_subject,reviewed_at,version)
                    VALUES (?,?,'MEDIUM',?,'[]',?,'legacy-reviewer','2026-09-01T00:00:00Z',7)
                    """, policy, enterprise, "V30存量分析-" + status, status);
        }
        jdbc.update("INSERT INTO enterprise(id,association_id,name,status) VALUES (?,?,'摘要企业','ACTIVE')",
                summaryEnterprise, association);
        String importId = "upgrade-source-" + association;
        jdbc.update("""
                INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report)
                VALUES (?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'synthetic-upgrade-fixture.json','isolated-test','{"fixture":true}')
                """, importId, association);
        for (String kind : previousVersion.equals("30.1") ? List.of("TENDER", "ACTIVITY") : List.of("TENDER")) {
            jdbc.update("""
                    INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
                    VALUES (gen_random_uuid(),?,?,?,?,'隔离升级资料',
                            '{"source":{"原始摘要":"保持原样"},"publicEvidence":{"record":{"id":"isolated-fixture"}}}')
                    """, importId, association, kind, "TEST-" + kind);
        }
        List<String> sourcesBefore = jdbc.queryForList(
                "SELECT to_jsonb(s)::text FROM platform_source_record s ORDER BY id", String.class);
        List<String> importsBefore = jdbc.queryForList(
                "SELECT to_jsonb(i)::text FROM platform_dataset_import i ORDER BY id", String.class);
        List<String> legacyBefore = jdbc.queryForList(
                "SELECT to_jsonb(a)::text FROM policy_impact_analysis a ORDER BY id", String.class);
        List<String> enterprisesBefore = jdbc.queryForList(
                "SELECT to_jsonb(e)::text FROM enterprise e ORDER BY id", String.class);
        List<String> policiesBefore = jdbc.queryForList(
                "SELECT to_jsonb(p)::text FROM policy_document p ORDER BY id", String.class);

        var upgrade = Flyway.configure().dataSource(dataSource).target("31").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(previousVersion.equals("30.1") ? 1 : 2);
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("31");
        assertThat(jdbc.queryForList("""
                SELECT (to_jsonb(a) - 'evidence_details')::text FROM policy_impact_analysis a ORDER BY id
                """, String.class)).containsExactlyElementsOf(legacyBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis WHERE evidence_details IS NULL",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT to_jsonb(e)::text FROM enterprise e ORDER BY id", String.class))
                .containsExactlyElementsOf(enterprisesBefore);
        assertThat(jdbc.queryForList("SELECT to_jsonb(p)::text FROM policy_document p ORDER BY id", String.class))
                .containsExactlyElementsOf(policiesBefore);
        assertThat(jdbc.queryForList("SELECT to_jsonb(s)::text FROM platform_source_record s ORDER BY id", String.class))
                .containsExactlyElementsOf(sourcesBefore);
        assertThat(jdbc.queryForList("SELECT to_jsonb(i)::text FROM platform_dataset_import i ORDER BY id", String.class))
                .containsExactlyElementsOf(importsBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_index WHERE indexrelid='platform_policy_source_lookup_idx'::regclass AND indisvalid
                """, Integer.class)).isEqualTo(1);

        UUID summaryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO policy_impact_analysis(id,policy_document_id,enterprise_id,impact_level,summary,evidence_details)
                VALUES (?,?,?,'MEDIUM','仅供参考的摘要','{"basis":"SUMMARY_REFERENCE"}')
                """, summaryId, policy, summaryEnterprise);
        assertThatThrownBy(() -> jdbc.update("UPDATE policy_impact_analysis SET status='APPROVED' WHERE id=?", summaryId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("summary_analysis_not_approved");
        for (String malformed : List.of("{}", "[]", "{\"basis\":null}", "{\"basis\":\"UNKNOWN\"}")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE policy_impact_analysis SET evidence_details=?::jsonb WHERE id=?", malformed, summaryId))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("policy_analysis_evidence_object");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM policy_impact_analysis WHERE id=?", String.class, summaryId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis", Integer.class)).isEqualTo(4);
    }
}
