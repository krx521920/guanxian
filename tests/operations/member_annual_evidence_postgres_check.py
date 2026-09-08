"""Real PostgreSQL integration in a disposable container; never connects to production."""
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/operations'))
sys.path.insert(0, str(Path(__file__).parent))
import import_member_annual_evidence as op
from test_import_member_annual_evidence import fixture


def main():
    name = 'gx-annual-evidence-test-' + uuid.uuid4().hex[:12]
    created = False
    def psql(sql, ok=True):
        result = subprocess.run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-d', 'guanxian',
                                 '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1'],
                                input=sql, encoding='utf-8', capture_output=True, timeout=180)
        if ok and result.returncode: raise AssertionError(result.stderr)
        if not ok and not result.returncode: raise AssertionError('Expected guarded rejection')
        return result.stdout.strip()
    try:
        subprocess.run(['docker','run','--detach','--rm','--pull=never','--name',name,
                        '--label','guanxian.test=annual-evidence','--env','POSTGRES_DB=guanxian',
                        '--env','POSTGRES_PASSWORD=isolated-test-only','postgres:16-alpine'], check=True, timeout=60)
        created = True
        for _ in range(40):
            if subprocess.run(['docker','exec',name,'pg_isready','-h','127.0.0.1','-U','postgres','-d','guanxian'],
                              capture_output=True,timeout=10).returncode == 0: break
            time.sleep(1)
        else: raise AssertionError('Test PostgreSQL not ready')
        psql('CREATE TABLE flyway_schema_history(installed_rank integer primary key,version varchar(50),success boolean)')
        for rank, file in enumerate(sorted((ROOT / 'apps/server/bootstrap/src/main/resources/db/migration').glob('V*__*.sql'),
                           key=lambda f: tuple(map(int, f.name.split('__')[0][1:].split('_')))), 1):
            v = file.name.split('__')[0][1:].replace('_', '.')
            psql('BEGIN;\n'+file.read_text(encoding='utf-8')+f"\nINSERT INTO flyway_schema_history VALUES({rank},'{v}',true); COMMIT;")
        _, _, plan = fixture()
        psql(f"INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES"
             f"('{op.BASE_IMPORT}','{op.ASSOCIATION}','{op.BASE_SOURCE_SHA}','fixture','test','{{}}')")
        for r in plan['identities']:
            ident = str(uuid.uuid4())
            psql(f"INSERT INTO enterprise(id,association_id,name,status) SELECT '{ident}','{op.ASSOCIATION}',"
                 f"{op.json_sql(r)}->>'enterpriseName','ACTIVE'")
            psql(f"INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,enterprise_id,payload) "
                 f"SELECT gen_random_uuid(),'{op.BASE_IMPORT}','{op.ASSOCIATION}','ENTERPRISE',"
                 f"x->>'sourceId',x->>'enterpriseName','{ident}',x FROM (SELECT {op.json_sql(r)} AS x) s")
        original_id = str(uuid.uuid4())
        original = {'source': {'项目编号': 'S110000A001044613001', '发布日期': '2026-06-01', '采购内容': '原始资料必须保留'},
                    'correction': {'id':'preserved-correction','fields':{'采购内容':'更正资料也保留'}}}
        psql(f"INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload) "
             f"VALUES('{original_id}','{op.BASE_IMPORT}','{op.ASSOCIATION}','TENDER','BID-049','原项目',{op.json_sql(original)})")
        psql("INSERT INTO user_account(id,username,display_name) VALUES(gen_random_uuid(),'keep-user','保留账号')")
        preserved_sql = "SELECT jsonb_build_object(" + ','.join(
            f"'{table}',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM {table} t)" for table in
            ['enterprise','user_account','cooperation_demand','ecosystem_match','product_service','policy_document']) + ")"
        before = psql(preserved_sql)
        read, write, rollback = op.render_sql(plan), op.render_sql(plan, execute=True), op.render_sql(plan, execute=True, rollback=True)
        psql(read)
        assert psql('SELECT count(*) FROM platform_source_record') == '33'
        # Version guard, live enterprise identity guard and source/project duplicate guard.
        psql("UPDATE flyway_schema_history SET success=false WHERE version='30.1'"); psql(write, ok=False)
        psql("UPDATE flyway_schema_history SET success=true WHERE version='30.1'")
        first = plan['identities'][0]
        psql(f"UPDATE enterprise SET name='人工更名' WHERE name={op.json_sql(first)}->>'enterpriseName'")
        psql(write, ok=False)
        psql(f"UPDATE enterprise SET name={op.json_sql(first)}->>'enterpriseName' WHERE name='人工更名'")
        duplicate = plan['records'][0]
        psql(f"INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload) VALUES"
             f"(gen_random_uuid(),'{op.BASE_IMPORT}','{op.ASSOCIATION}','TENDER','DUPLICATE','重复链接',"
             f"jsonb_build_object('sourceUrl',{op.json_sql(duplicate)}->'payload'->>'sourceUrl'))")
        psql(write, ok=False)
        psql("DELETE FROM platform_source_record WHERE source_id='DUPLICATE'")
        psql(write.replace('\nCOMMIT;', '\nSELECT 1/0; COMMIT;'), ok=False)
        assert psql('SELECT count(*) FROM platform_source_record') == '33'
        report = json.loads(psql(write)); assert report['storedRows'] == 63 and report['evidenceRows'] == 64
        assert psql("SELECT count(*) FROM platform_source_record WHERE kind='ACTIVITY'") == '23'
        assert psql("SELECT count(*) FROM audit_log WHERE action='IMPORT_PUBLIC_EVIDENCE'") == '64'
        psql(write); psql(read)
        assert psql("SELECT count(*) FROM audit_log WHERE action='IMPORT_PUBLIC_EVIDENCE'") == '64'
        assert psql(preserved_sql) == before
        assert psql(f"SELECT payload-'publicEvidence'={op.json_sql(original)} FROM platform_source_record WHERE id='{original_id}'") == 't'
        # Both repeat apply and rollback refuse a user-edited enrichment.
        psql(f"UPDATE platform_source_record SET title='人工修改项目' WHERE id='{original_id}'")
        psql(write, ok=False); psql(rollback, ok=False)
        psql(f"UPDATE platform_source_record SET title='原项目' WHERE id='{original_id}'")
        psql(rollback)
        assert psql('SELECT count(*) FROM platform_source_record') == '33'
        assert psql(f"SELECT payload={op.json_sql(original)} FROM platform_source_record WHERE id='{original_id}'") == 't'
        assert psql(preserved_sql) == before
        assert psql("SELECT count(*) FROM audit_log WHERE action='IMPORT_PUBLIC_EVIDENCE'") == '64'
        assert psql("SELECT count(*) FROM audit_log WHERE action='ROLLBACK_PUBLIC_EVIDENCE'") == '1'
        print(json.dumps({'status':'passed','engine':'real PostgreSQL 16','records':64,'new':63,'enriched':1,
                          'checks':['schema-guard','live-identity','duplicate-url','read-only','atomic-rollback','role-evidence-preserved',
                                    'activity-separated','idempotency','manual-edit-conflict','business-unchanged','scoped-rollback-audit-retained']}))
    finally:
        if created: subprocess.run(['docker','stop','--timeout','10',name],check=True,timeout=30)


if __name__ == '__main__': main()
