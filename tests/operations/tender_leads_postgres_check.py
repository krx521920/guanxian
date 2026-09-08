"""Test append-only tender SQL in a new, owned container, never a deployment database."""
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/operations'))
sys.path.insert(0, str(Path(__file__).parent))
from prepare_tender_leads import prepare, render_sql, ASSOCIATION
from test_prepare_tender_leads import catalog_fixture, blob


def main():
    name = 'gx-tender-leads-test-' + uuid.uuid4().hex[:12]
    created = False

    def psql(sql, ok=True):
        result = subprocess.run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-d', 'guanxian',
                                 '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1'],
                                input=sql, text=True, encoding='utf-8', capture_output=True, timeout=180)
        if ok and result.returncode: raise AssertionError(result.stderr)
        if not ok and not result.returncode: raise AssertionError('Expected conflict/rollback')
        return result.stdout.strip()

    try:
        subprocess.run(['docker', 'run', '--detach', '--rm', '--pull=never', '--name', name,
                        '--label', 'guanxian.test=tender-leads', '--env', 'POSTGRES_DB=guanxian',
                        '--env', 'POSTGRES_PASSWORD=isolated-test-only', 'postgres:16-alpine'], check=True, timeout=60)
        created = True
        for _ in range(30):
            if subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'guanxian'],
                              capture_output=True, timeout=10).returncode == 0: break
            time.sleep(1)
        else: raise AssertionError('Test PostgreSQL not ready')
        psql('CREATE TABLE flyway_schema_history(installed_rank integer primary key,version varchar(50),success boolean)')
        for rank, path in enumerate(sorted((ROOT / 'apps/server/bootstrap/src/main/resources/db/migration').glob('V*__*.sql'),
                           key=lambda p: tuple(map(int, p.name.split('__')[0][1:].split('_')))), 1):
            v = path.name.split('__')[0][1:].replace('_', '.')
            psql('BEGIN;\n' + path.read_text(encoding='utf-8') + f"\nINSERT INTO flyway_schema_history VALUES({rank},'{v}',true); COMMIT;")
        psql(f"INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) "
             f"VALUES('existing','{ASSOCIATION}','{'a'*64}','fixture','test','{{}}')")
        old = str(uuid.uuid4())
        psql(f"INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload) "
             f"VALUES('{old}','existing','{ASSOCIATION}','TENDER','OLD','原有资料','{{}}')")
        psql(f"INSERT INTO enterprise(id,association_id,name,status) VALUES(gen_random_uuid(),'{ASSOCIATION}','保留企业','ACTIVE')")
        psql("INSERT INTO user_account(id,username,display_name) VALUES(gen_random_uuid(),'preserve-account','保留账号')")
        preserved_sql = """SELECT jsonb_build_object('enterprise',(SELECT jsonb_agg(to_jsonb(e)) FROM enterprise e),
          'user',(SELECT jsonb_agg(to_jsonb(u)) FROM user_account u),
          'demands',(SELECT jsonb_agg(to_jsonb(d)) FROM cooperation_demand d),
          'matches',(SELECT jsonb_agg(to_jsonb(m)) FROM ecosystem_match m))"""
        preserved = psql(preserved_sql)
        plan = prepare(blob(catalog_fixture()), {})
        read, write = render_sql(plan), render_sql(plan, execute=True)
        psql(read)
        assert psql('SELECT count(*) FROM platform_source_record') == '1'
        first = plan['records'][0]
        for payload in (json.dumps({'sourceUrl': first['sourceUrl'].replace('https:', 'http:')}),
                        json.dumps({'source': {'项目编号': first['source']['项目编号']}}),
                        json.dumps({'publicEvidence': {'record': {'sourceUrl': first['sourceUrl']}}}),
                        json.dumps({'publicEvidence': {'fields': {'项目编号': first['source']['项目编号']}}}),
                        json.dumps({'publicEvidence': {'record': {'projectNumber': first['source']['项目编号']}}}),
                        json.dumps({'publicEvidence': {'supportingUrls': [first['sourceUrl']]}}),
                        json.dumps({'publicEvidence': {'record': {'supportingUrls': [first['sourceUrl']]}}}),
                        json.dumps({'sourceLinks': [{'url': first['sourceUrl']}]}),
                        json.dumps({'correction': {'fields': {'原文链接': first['sourceUrl']}}})):
            psql(f"UPDATE platform_source_record SET payload='{payload}' WHERE id='{old}'")
            psql(read, ok=False)
            psql(write, ok=False)
        psql(f"UPDATE platform_source_record SET payload='{{}}' WHERE id='{old}'")
        psql(write.replace('\nCOMMIT;', '\nSELECT 1/0; COMMIT;'), ok=False)
        assert psql('SELECT count(*) FROM platform_source_record') == '1'
        psql(write)
        assert psql('SELECT count(*) FROM platform_source_record') == '7'
        assert psql("SELECT count(*) FROM audit_log WHERE action='IMPORT_EXTERNAL_TENDER'") == '6'
        psql(write); psql(read)
        assert psql("SELECT count(*) FROM audit_log WHERE action='IMPORT_EXTERNAL_TENDER'") == '6'
        assert psql(preserved_sql) == preserved
        assert psql(f"SELECT payload='{{}}'::jsonb FROM platform_source_record WHERE id='{old}'") == 't'
        psql(f"UPDATE platform_source_record SET title='人工修改' WHERE id='{first['id']}'")
        psql(write, ok=False)
        print(json.dumps({'status': 'passed', 'checks': ['readonly', 'url-dedup', 'project-dedup', 'atomic-rollback',
          'annual-evidence-and-correction-dedup', 'six-inserted', 'audit', 'idempotency', 'existing-data-preserved', 'manual-edit-conflict']}))
    finally:
        if created: subprocess.run(['docker', 'stop', '--timeout', '10', name], check=True, timeout=30)


if __name__ == '__main__': main()
