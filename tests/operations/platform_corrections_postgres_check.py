"""Run correction SQL against a new, owned PostgreSQL container with synthetic records only."""
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/operations"))
sys.path.insert(0, str(Path(__file__).parent))
from prepare_platform_corrections import build_plan, render_sql, ASSOCIATION, RUN, SOURCE_SHA, IMPORT_ACTOR, json_sql
from test_prepare_platform_corrections import correction_fixture


def main():
    name = "gx-source-correction-test-" + uuid.uuid4().hex[:12]
    created = False

    def psql(sql, *, ok=True):
        result = subprocess.run(["docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "guanxian",
                                 "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
                                input=sql, text=True, encoding="utf-8", capture_output=True, timeout=180)
        if ok and result.returncode:
            raise AssertionError(result.stderr)
        if not ok and result.returncode == 0:
            raise AssertionError("Conflict or failed transaction was accepted")
        return result.stdout.strip()

    try:
        subprocess.run(["docker", "run", "--detach", "--rm", "--pull=never", "--name", name,
                        "--label", "guanxian.test=source-corrections", "--env", "POSTGRES_DB=guanxian",
                        "--env", "POSTGRES_PASSWORD=local-isolated-test-only", "postgres:16-alpine"], check=True, timeout=60)
        created = True
        for _ in range(30):
            if subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "guanxian"],
                              capture_output=True, timeout=10).returncode == 0:
                break
            time.sleep(1)
        else:
            raise AssertionError("Test PostgreSQL did not become ready")
        psql("CREATE TABLE flyway_schema_history(installed_rank integer primary key,version varchar(50),success boolean)")
        for rank, path in enumerate(sorted((ROOT / "apps/server/bootstrap/src/main/resources/db/migration").glob("V*__*.sql"),
                           key=lambda p: tuple(map(int, p.name.split("__")[0][1:].split('_')))), 1):
            version = path.name.split("__")[0][1:].replace('_', '.')
            psql("BEGIN;\n" + path.read_text(encoding="utf-8") +
                 f"\nINSERT INTO flyway_schema_history VALUES({rank},'{version}',true); COMMIT;")
        plan = build_plan(correction_fixture())
        entries = plan["entries"]
        psql(f"INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) "
             f"VALUES('{RUN}','{ASSOCIATION}','{SOURCE_SHA}','synthetic-fixture','test','{{}}')")
        for entry in entries:
            r = entry["before"]
            if entry["kind"] == "POLICY":
                psql(f"""INSERT INTO policy_document(id,association_id,title,document_number,effective_on,published_on,
                    source_url,summary,issuing_authority,category,policy_level,tags,status,visibility,created_by_subject,updated_by_subject)
                    SELECT (r->>'id')::uuid,'{ASSOCIATION}',r->>'title',r->>'documentNumber',(r->>'effectiveOn')::date,
                    (r->>'publishedOn')::date,r->>'sourceUrl',r->>'summary',r->>'issuingAuthority',r->>'category',r->>'region',
                    jsonb_build_array(r->'source'->>'涉及领域'),'PUBLISHED','MEMBERS','{IMPORT_ACTOR}','{IMPORT_ACTOR}'
                    FROM (SELECT {json_sql(r)} AS r) data;""")
            policy_reference = "(r->>'id')::uuid" if entry["kind"] == "POLICY" else "NULL"
            psql(f"""INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,policy_id,payload)
                SELECT (r->>'recordId')::uuid,'{RUN}','{ASSOCIATION}','{entry['kind']}',r->>'sourceId',r->>'title',
                {policy_reference},r FROM (SELECT {json_sql(r)} AS r) data;""")
        enterprise, account = str(uuid.uuid4()), str(uuid.uuid4())
        psql(f"INSERT INTO enterprise(id,association_id,name,status) VALUES('{enterprise}','{ASSOCIATION}','保留测试企业','ACTIVE');"
             f"INSERT INTO user_account(id,username,display_name) VALUES('{account}','preserved-fixture','测试账号');"
             f"INSERT INTO policy_impact_analysis(policy_document_id,enterprise_id,impact_level,summary) "
             f"VALUES('{entries[0]['before']['id']}','{enterprise}','LOW','保持原有分析')")
        preserved = psql("SELECT jsonb_build_object('enterprises',(SELECT jsonb_agg(to_jsonb(e)) FROM enterprise e),"
                         "'users',(SELECT jsonb_agg(to_jsonb(u)) FROM user_account u),"
                         "'analyses',(SELECT jsonb_agg(to_jsonb(a)) FROM policy_impact_analysis a))")
        state_sql = "SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM platform_source_record r"
        before = psql(state_sql)
        read, write = render_sql(plan), render_sql(plan, execute=True)
        psql(read)
        assert psql(state_sql) == before
        policy_id = entries[0]["before"]["id"]
        psql(f"UPDATE policy_document SET version=1 WHERE id='{policy_id}'")
        psql(write, ok=False)
        psql(f"UPDATE policy_document SET version=0 WHERE id='{policy_id}'")
        # Also protect field edits made without an optimistic-version increment.
        psql(f"UPDATE policy_document SET summary='manual-edit' WHERE id='{policy_id}'")
        psql(write, ok=False)
        psql(f"UPDATE policy_document SET summary={json_sql(entries[0]['before'])}->>'summary' WHERE id='{policy_id}'")
        tender_id = entries[2]["recordId"]
        psql(f"UPDATE platform_source_record SET payload=payload||'{{\"manualEdit\":true}}'::jsonb WHERE id='{tender_id}'")
        psql(write, ok=False)
        assert psql("SELECT count(*) FROM business_entity_history WHERE action='SOURCE_METADATA_CORRECTED'") == "0"
        psql(f"UPDATE platform_source_record SET payload=payload-'manualEdit' WHERE id='{tender_id}'")
        psql(f"UPDATE platform_source_record SET payload={json_sql(entries[2]['after'])} WHERE id='{tender_id}'")
        psql(write, ok=False)
        psql(f"UPDATE platform_source_record SET payload={json_sql(entries[2]['before'])} WHERE id='{tender_id}'")
        psql(write.replace("\nCOMMIT;", "\nSELECT 1/0; COMMIT;"), ok=False)
        assert psql(state_sql) == before
        psql(write)
        assert psql("SELECT count(*) FROM platform_source_record WHERE payload ? 'correction'") == "3"
        assert psql("SELECT count(*) FROM policy_document WHERE version=1") == "2"
        for entry in entries:
            assert psql(f"SELECT payload->'source'={json_sql(entry['before']['source'])} FROM platform_source_record WHERE id='{entry['recordId']}'") == "t"
        assert psql(f"SELECT document_number||'|'||effective_on::text FROM policy_document WHERE id='{policy_id}'") == "GB/T 50838-2015|2025-04-01"
        assert psql(f"SELECT effective_on IS NULL FROM policy_document WHERE id='{entries[1]['before']['id']}'") == "t"
        # These predicates are the matching/analysis source-snapshot contract.
        assert psql("""SELECT count(*) FROM policy_document p JOIN platform_source_record s ON s.policy_id=p.id
            WHERE s.payload->>'title'=p.title AND s.payload->>'summary' IS NOT DISTINCT FROM p.summary
            AND s.payload->>'sourceUrl' IS NOT DISTINCT FROM p.source_url
            AND s.payload->>'category' IS NOT DISTINCT FROM p.category
            AND s.payload->>'region' IS NOT DISTINCT FROM p.policy_level
            AND s.payload->>'effectiveOn' IS NOT DISTINCT FROM p.effective_on::text
            AND p.tags=jsonb_build_array(s.payload->'source'->>'涉及领域')""") == "2"
        assert psql("SELECT count(*) FROM business_entity_history WHERE action='SOURCE_METADATA_CORRECTED'") == "5"
        assert psql("SELECT count(*) FROM audit_log WHERE action='SOURCE_METADATA_CORRECTED'") == "3"
        psql(write); psql(read)
        assert psql("SELECT count(*) FROM audit_log WHERE action='SOURCE_METADATA_CORRECTED'") == "3"
        assert psql("SELECT jsonb_build_object('enterprises',(SELECT jsonb_agg(to_jsonb(e)) FROM enterprise e),"
                    "'users',(SELECT jsonb_agg(to_jsonb(u)) FROM user_account u),"
                    "'analyses',(SELECT jsonb_agg(to_jsonb(a)) FROM policy_impact_analysis a))") == preserved
        psql(f"UPDATE policy_document SET version=2 WHERE id='{policy_id}'")
        psql(write, ok=False)
        print(json.dumps({"status": "passed", "checks": ["schema-SQL", "read-only", "policy-version-conflict", "policy-field-conflict", "source-conflict",
            "partial-batch-conflict", "atomic-rollback", "corrected-values", "matching-source-contract", "originals-preserved", "audit-history", "idempotency", "accounts-enterprises-analyses-unchanged"]}))
    finally:
        if created:
            subprocess.run(["docker", "stop", "--timeout", "10", name], check=True, timeout=30)


if __name__ == "__main__":
    main()
