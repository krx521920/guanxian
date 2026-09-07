"""Real PostgreSQL safety test in a disposable, explicitly owned Docker container.

Uses synthetic CSV rows only. Applies all checked-in schema SQL before testing the
operation; Flyway execution itself is covered by SourceDirectoryPostgresTest.
Never invokes the production CLI or contacts any deployment/SSH endpoint.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/operations"))
sys.path.insert(0, str(Path(__file__).parent))
from apply_platform_dataset import ASSOCIATION, COMPANIES, DEMO_BATCH, DEMO_BATCH_SHA, RUN, render_sql
from prepare_platform_dataset import prepare
from test_prepare_platform_dataset import PrepareDatasetTests


def main():
    name = "gx-source-import-test-" + uuid.uuid4().hex[:12]
    created = False
    def psql(sql, *, ok=True):
        result = subprocess.run(["docker", "exec", "-i", name, "psql", "-U", "postgres", "-d", "guanxian",
                                 "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
                                input=sql, text=True, encoding="utf-8", capture_output=True, timeout=180)
        if ok and result.returncode:
            raise AssertionError(result.stderr)
        if not ok and result.returncode == 0:
            raise AssertionError("Unsafe operation was accepted")
        return result.stdout.strip()
    try:
        subprocess.run(["docker", "run", "--detach", "--rm", "--name", name,
                        "--label", "guanxian.test=platform-source-import", "--env", "POSTGRES_DB=guanxian",
                        "--env", "POSTGRES_PASSWORD=local-integration-test-only", "postgres:16-alpine"], check=True, timeout=120)
        created = True
        for _ in range(45):
            # The entrypoint briefly starts a Unix-socket-only server before
            # creating POSTGRES_DB. Wait for the final TCP listener, not that
            # temporary bootstrap server, before applying schema SQL.
            ready = subprocess.run(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1",
                                    "-U", "postgres", "-d", "guanxian"], capture_output=True, timeout=10)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise AssertionError("Isolated PostgreSQL did not become ready")
        psql("CREATE TABLE flyway_schema_history(installed_rank integer primary key,version varchar(50),success boolean)")
        migrations = sorted((ROOT / "apps/server/bootstrap/src/main/resources/db/migration").glob("V*__*.sql"),
                            key=lambda p: int(p.name.split("__")[0][1:]))
        for migration in migrations:
            version = int(migration.name.split("__")[0][1:])
            psql("BEGIN;\n" + migration.read_text(encoding="utf-8") +
                 f"\nINSERT INTO flyway_schema_history VALUES({version},'{version}',true); COMMIT;")
        for i, id_ in enumerate(COMPANIES):
            psql(f"INSERT INTO enterprise(id,association_id,name,category,status,version) VALUES('{id_}','{ASSOCIATION}',"
                 f"'验收测试企业{i+1:02d}（虚构）','测试','ACTIVE',{4 if i==4 else 2})")
        psql(f"INSERT INTO member_import_batch(id,association_id,original_filename,status,total_rows,valid_rows,invalid_rows,created_by_subject,source_sha256) "
             f"VALUES('{DEMO_BATCH}','{ASSOCIATION}','synthetic-demo.xlsx','COMMITTED',5,5,0,'fixture','{DEMO_BATCH_SHA}')")
        for i, id_ in enumerate(COMPANIES):
            psql(f"INSERT INTO member_import_row(batch_id,row_number,status,enterprise_id) VALUES('{DEMO_BATCH}',{i+2},'IMPORTED','{id_}')")
        seed = (ROOT / "tools/operations/showcase_seed.sql").read_text(encoding="utf-8").replace(":'expected_database'", "'guanxian'")
        psql(seed)
        psql("UPDATE ecosystem_match SET version=1 WHERE id='d3000000-0000-0000-0000-000000000401'")
        for id_, owner in [("0e243780-9e43-4a03-8345-2c839ce1bb57",4),("9a3f8fcf-586b-4ef7-b151-a452977afe79",2)]:
            psql(f"INSERT INTO ecosystem_match(id,demand_id,candidate_enterprise_id,score) VALUES('{id_}',"
                 f"'d3000000-0000-0000-0000-000000000201','{COMPANIES[owner]}',10)")
        psql(f"INSERT INTO enterprise(id,association_id,name,status) VALUES('f1000000-0000-0000-0000-000000000001','{ASSOCIATION}','真实保留单位','ACTIVE');"
             "INSERT INTO user_account(id,username,display_name) VALUES('f1000000-0000-0000-0000-000000000002','keep-account','Keep account')")
        blob, _ = PrepareDatasetTests().fixture()
        bundle = prepare(blob, hashlib.sha256(blob).hexdigest())
        # Match this fixture's membership split to the approved input shape.
        for row in bundle["enterprises"][:101]:
            row["membershipEvidence"] = "SOURCE_LISTED_NOT_INDEPENDENTLY_VERIFIED"
        readonly = render_sql(bundle)
        write = render_sql(bundle, execute=True)
        psql(readonly)
        assert psql("SELECT count(*) FROM enterprise WHERE deleted_at IS NULL") == "6"
        assert psql("SELECT count(*) FROM platform_dataset_import") == "0"
        psql(f"UPDATE enterprise SET version=version+1 WHERE id='{COMPANIES[0]}'")
        psql(write, ok=False)
        assert psql("SELECT count(*) FROM platform_dataset_import") == "0"
        psql(f"UPDATE enterprise SET version=version-1 WHERE id='{COMPANIES[0]}'")
        psql(f"UPDATE user_account SET enterprise_id='{COMPANIES[0]}' WHERE username='keep-account'")
        psql(write, ok=False)
        psql("UPDATE user_account SET enterprise_id=NULL WHERE username='keep-account'")
        # Test actual transaction rollback after all writes, then the committed path.
        psql(write.replace("\nCOMMIT;", "\nROLLBACK;"))
        assert psql("SELECT count(*) FROM platform_dataset_import") == "0"
        assert psql("SELECT count(*) FROM enterprise WHERE deleted_at IS NULL") == "6"
        psql(write)
        assert psql("SELECT count(*) FROM enterprise WHERE deleted_at IS NULL") == "117"
        assert psql("SELECT count(*) FROM enterprise WHERE deleted_at IS NULL AND status='PENDING_REVIEW'") == "15"
        assert psql("SELECT count(*) FROM policy_document WHERE deleted_at IS NULL") == "40"
        assert psql("SELECT count(*) FROM ecosystem_match WHERE deleted_at IS NULL") == "0"
        assert psql("SELECT count(*) FROM policy_impact_analysis") == "0"
        assert psql("SELECT count(*) FROM platform_source_record") == "211"
        assert psql("SELECT count(*) FROM association") == "1"
        assert psql("SELECT count(*) FROM user_account WHERE username='keep-account' AND enterprise_id IS NULL") == "1"
        assert psql("SELECT count(*) FROM enterprise WHERE name='真实保留单位' AND deleted_at IS NULL") == "1"
        psql(write)
        assert psql("SELECT count(*) FROM platform_source_record") == "211"
        assert psql(f"SELECT count(*) FROM platform_dataset_import WHERE id='{RUN}'") == "1"
        print(json.dumps({"status":"passed","checks":["full-schema","read-only-preflight","changed-row-guard",
              "bound-account-guard","transaction-rollback","211-record-import","preserve-real-enterprise",
              "preserve-account","no-sharing-grants","idempotent-rerun"]}))
    finally:
        if created:
            subprocess.run(["docker", "rm", "--force", name], check=True, timeout=30)


if __name__ == "__main__":
    main()
