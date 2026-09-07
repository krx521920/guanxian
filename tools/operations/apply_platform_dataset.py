"""Guarded one-time import of the approved Beijing source archive.

Default: database read-only preflight. --execute requires the exact source hash,
root/admin execution, schema V30, unchanged reviewed demo rows, and a new backup.
No account/password/consent changes. Source data is supplied at runtime, not in Git.
"""
from __future__ import annotations
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

from prepare_platform_dataset import prepare

SOURCE_SHA = "03b0929d31d163f51461eb7f8011b14c356c02d4f7ccb5bdf2040103920f9daa"
ASSOCIATION = "00000000-0000-0000-0000-000000000106"
RUN = "beijing-platform-20260907"
ACTOR = "maintenance:platform-import-20260907"
DEMO_ACTOR = "showcase-seed:2026-09-04"
DEMO_BATCH = "c8680e26-a89b-4066-b13b-24355a6d9607"
DEMO_BATCH_SHA = "918b62633fc5faeff1dce6543f5cd27c9bacb8cc276418897750cbb6ef072966"
COMPANIES = ["ee1dd720-aac2-4b92-abc5-d2e578eb11c2", "a3dd3542-daee-4632-881c-546d1c74c1a2",
             "66ddb2e7-dd60-46de-9a86-b61829118059", "ddc6ec8b-64d9-480e-a68b-7d53873cbcf0",
             "14b29d67-ec90-4645-80d5-7369d923cc31"]


def seed_id(number: int) -> str:
    return f"d3000000-0000-0000-0000-{number:012d}"


def reviewed_targets() -> dict:
    result = {"enterprise": [{"id": id_, "name": f"验收测试企业{i+1:02d}（虚构）", "version": 4 if i == 4 else 2,
               "association_id": ASSOCIATION, "status": "ACTIVE", "deleted_at": None} for i, id_ in enumerate(COMPANIES)]}
    for table, start, owners in (("product_service", 101, [0, 1, 2, 3, 4]), ("cooperation_demand", 201, [3, 0, 1, 2])):
        result[table] = [{"id": seed_id(start+i), "version": 0, "created_by_subject": DEMO_ACTOR,
                          "updated_by_subject": DEMO_ACTOR, "enterprise_id": COMPANIES[owner], "deleted_at": None}
                         for i, owner in enumerate(owners)]
    result["policy_document"] = [{"id": seed_id(301+i), "version": 0, "created_by_subject": DEMO_ACTOR,
         "association_id": ASSOCIATION, "status": "PUBLISHED", "deleted_at": None} for i in range(3)]
    result["ecosystem_match"] = [{"id": seed_id(401+i), "version": 1 if i == 0 else 0,
          "demand_id": seed_id(demand), "candidate_enterprise_id": COMPANIES[owner], "deleted_at": None}
          for i, (demand, owner) in enumerate([(201, 0), (201, 1), (202, 2), (202, 4), (203, 4), (204, 3)])]
    result["ecosystem_match"] += [{"id": id_, "version": 0, "demand_id": seed_id(201),
          "candidate_enterprise_id": COMPANIES[owner], "state": "PENDING_CONFIRMATION", "deleted_at": None}
          for id_, owner in [("0e243780-9e43-4a03-8345-2c839ce1bb57", 4), ("9a3f8fcf-586b-4ef7-b151-a452977afe79", 2)]]
    result["collaboration_task"] = [{"id": seed_id(501+i), "version": 0, "enterprise_id": COMPANIES[owner],
         "association_id": ASSOCIATION, "deleted_at": None} for i, owner in enumerate([3, 0, 1, 2])]
    result["knowledge_document"] = [{"id": seed_id(601+i), "lifecycle_version": 2,
         "created_by_subject": DEMO_ACTOR, "association_id": ASSOCIATION, "deleted_at": None} for i in range(2)]
    result["policy_impact_analysis"] = [{"id": seed_id(701+i), "version": 1 if i < 2 else 0,
         "policy_document_id": seed_id(policy), "enterprise_id": COMPANIES[owner]}
         for i, (policy, owner) in enumerate([(301, 1), (302, 0), (303, 3), (302, 4)])]
    return result


def json_sql(value: object) -> str:
    # Only base64's fixed alphabet reaches SQL source, never raw source prose.
    encoded = base64.b64encode(json.dumps(value, ensure_ascii=False).encode()).decode()
    return f"convert_from(decode('{encoded}','base64'),'UTF8')::jsonb"


def mapped_bundle(bundle: dict) -> dict:
    result = json.loads(json.dumps(bundle))
    for key, kind in (("enterprises", "ENTERPRISE"), ("policies", "POLICY"),
                      ("associationDirectory", "ASSOCIATION"), ("externalOpportunities", "TENDER")):
        for record in result[key]:
            record["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, f"guanxian:{SOURCE_SHA}:{kind}:{record['sourceId']}"))
            record["recordId"] = str(uuid.uuid5(uuid.NAMESPACE_URL, f"guanxian:source:{SOURCE_SHA}:{kind}:{record['sourceId']}"))
    return result


def render_sql(bundle: dict, *, execute: bool = False) -> str:
    data = mapped_bundle(bundle)
    targets = reviewed_targets()
    ids = [r["id"] for rows in targets.values() for r in rows]
    header = "BEGIN;" if execute else "BEGIN READ ONLY;"
    # The write transaction rechecks the same preflight under table locks.
    locks = """LOCK TABLE association,enterprise,user_account,enterprise_owner_grant,enterprise_owner_invitation,
      enterprise_managed_account,enterprise_profile_workflow,object_file,product_service,cooperation_demand,
      policy_document,ecosystem_match,collaboration_task,knowledge_document,policy_impact_analysis,
      cross_association_recommendation,outcome_archive,member_import_batch,member_import_row,
      enterprise_share_consent,match_invitation,negotiation_record,match_feedback,notification_message,outbox_event,
      platform_dataset_import,platform_source_record IN SHARE ROW EXCLUSIVE MODE;""" if execute else ""
    guard = f"""
{header}
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='120s';
{locks}
DO $gx_import$
DECLARE
  gx_data jsonb := {json_sql(data)};
  gx_targets jsonb := {json_sql(targets)};
  gx_ids uuid[] := ARRAY(SELECT jsonb_array_elements_text({json_sql(ids)}))::uuid[];
  gx_enterprises uuid[] := ARRAY(SELECT jsonb_array_elements_text({json_sql(COMPANIES)}))::uuid[];
  gx_table text; gx_row jsonb; gx_actual jsonb; gx_id uuid; gx_kind text; gx_key text;
  gx_report jsonb; gx_count integer;
BEGIN
  IF current_database()<>'guanxian' THEN RAISE EXCEPTION 'Wrong database'; END IF;
  IF NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='30' AND success) THEN
    RAISE EXCEPTION 'Deploy schema V30 before importing'; END IF;
  IF EXISTS(SELECT 1 FROM platform_dataset_import WHERE id='{RUN}' AND source_sha256='{SOURCE_SHA}') THEN
    RAISE NOTICE 'Dataset already imported; no rows changed'; RETURN; END IF;
  IF NOT EXISTS(SELECT 1 FROM association WHERE id='{ASSOCIATION}' AND name='北京地下管线协会' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'Unexpected association'; END IF;
  IF NOT EXISTS(SELECT 1 FROM member_import_batch WHERE id='{DEMO_BATCH}' AND source_sha256='{DEMO_BATCH_SHA}'
      AND association_id='{ASSOCIATION}' AND status='COMMITTED' AND total_rows=5) THEN
    RAISE EXCEPTION 'Demo enterprise provenance mismatch'; END IF;
  IF (SELECT count(*) FROM member_import_row WHERE batch_id='{DEMO_BATCH}' AND enterprise_id=ANY(gx_enterprises)
      AND status='IMPORTED')<>5 THEN RAISE EXCEPTION 'Demo member import links changed'; END IF;
  FOREACH gx_table IN ARRAY ARRAY['enterprise','product_service','cooperation_demand','policy_document',
      'ecosystem_match','collaboration_task','knowledge_document','policy_impact_analysis'] LOOP
    FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_targets->gx_table) LOOP
      EXECUTE format('SELECT to_jsonb(t) FROM %I t WHERE id=$1',gx_table) INTO gx_actual USING (gx_row->>'id')::uuid;
      IF gx_actual IS NULL OR NOT gx_actual @> gx_row THEN
        RAISE EXCEPTION 'Reviewed row changed: % %',gx_table,gx_row->>'id'; END IF;
    END LOOP;
  END LOOP;
  IF EXISTS(SELECT 1 FROM user_account WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM enterprise_owner_grant WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM enterprise_owner_invitation WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM enterprise_managed_account WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM enterprise_profile_workflow WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM enterprise_share_consent WHERE enterprise_id=ANY(gx_enterprises))
     OR EXISTS(SELECT 1 FROM object_file WHERE enterprise_id=ANY(gx_enterprises)) THEN
    RAISE EXCEPTION 'Demo enterprise has an account, workflow or file dependency; manual review required'; END IF;
  IF EXISTS(SELECT 1 FROM product_service WHERE enterprise_id=ANY(gx_enterprises) AND NOT id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM cooperation_demand WHERE enterprise_id=ANY(gx_enterprises) AND NOT id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM ecosystem_match WHERE (demand_id=ANY(gx_ids) OR candidate_enterprise_id=ANY(gx_enterprises)) AND NOT id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM collaboration_task WHERE (enterprise_id=ANY(gx_enterprises) OR match_id=ANY(gx_ids)) AND NOT id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM policy_impact_analysis WHERE (enterprise_id=ANY(gx_enterprises) OR policy_document_id=ANY(gx_ids)) AND NOT id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM cross_association_recommendation WHERE demand_id=ANY(gx_ids) OR match_id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM outcome_archive WHERE match_id=ANY(gx_ids)) THEN
    RAISE EXCEPTION 'Unreviewed dependent business records found'; END IF;
  IF EXISTS(SELECT 1 FROM match_invitation WHERE match_id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM negotiation_record WHERE match_id=ANY(gx_ids))
     OR EXISTS(SELECT 1 FROM match_feedback WHERE match_id=ANY(gx_ids)) THEN
    RAISE EXCEPTION 'Demo match has an unreviewed workflow dependency'; END IF;
  IF EXISTS(SELECT 1 FROM enterprise e JOIN jsonb_array_elements(gx_data->'enterprises') r ON
      e.association_id='{ASSOCIATION}' AND regexp_replace(translate(e.name,'（）','()'),'\\s','','g') =
      regexp_replace(translate(r->'profile'->>'name','（）','()'),'\\s','','g')) THEN
    RAISE EXCEPTION 'Existing real enterprise name collision; reconcile without overwriting'; END IF;
  IF EXISTS(SELECT 1 FROM policy_document p JOIN jsonb_array_elements(gx_data->'policies') r ON
      p.association_id='{ASSOCIATION}' AND (p.title=r->>'title' OR p.source_url=r->>'sourceUrl')) THEN
    RAISE EXCEPTION 'Existing real policy collision; reconcile without overwriting'; END IF;
  gx_report := jsonb_build_object('sourceSha256','{SOURCE_SHA}','imported',gx_data->'counts',
      'removedDemoCounts',jsonb_build_object('enterprises',5,'offerings',5,'demands',4,'policies',3,'matches',8,
        'collaborations',4,'knowledgeDocuments',2,'policyImpacts',4),
      'accountsChanged',false,'publicationsGranted',false,'associationSharingGranted',false);
"""
    if not execute:
        return guard + "RAISE NOTICE 'Read-only preflight passed';\nEND $gx_import$;\nROLLBACK;\n"
    body = f"""
  -- Keep audit snapshots and the server-side backup. No cascade deletion.
  FOREACH gx_table IN ARRAY ARRAY['enterprise','product_service','cooperation_demand','policy_document',
      'ecosystem_match','collaboration_task','knowledge_document','policy_impact_analysis'] LOOP
    FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_targets->gx_table) LOOP
      EXECUTE format('SELECT to_jsonb(t) FROM %I t WHERE id=$1',gx_table) INTO gx_actual USING (gx_row->>'id')::uuid;
      INSERT INTO business_entity_history(association_id,resource_type,resource_id,resource_version,action,actor_subject,snapshot)
      VALUES('{ASSOCIATION}',upper(gx_table),(gx_row->>'id')::uuid,
          coalesce((gx_row->>'version')::bigint,(gx_row->>'lifecycle_version')::bigint,0),
          'DEMO_CLEANUP','{ACTOR}',gx_actual);
    END LOOP;
  END LOOP;
  UPDATE enterprise SET status_before_delete=status,status='DELETED',deleted_at=now(),deleted_by_subject='{ACTOR}',
      version=version+1,updated_at=now() WHERE id=ANY(gx_enterprises);
  UPDATE product_service SET deleted_at=now(),disabled_at=now(),updated_at=now(),version=version+1,updated_by_subject='{ACTOR}' WHERE id=ANY(gx_ids);
  UPDATE cooperation_demand SET deleted_at=now(),disabled_at=now(),updated_at=now(),version=version+1,updated_by_subject='{ACTOR}' WHERE id=ANY(gx_ids);
  UPDATE policy_document SET deleted_at=now(),disabled_at=now(),updated_at=now(),version=version+1,updated_by_subject='{ACTOR}' WHERE id=ANY(gx_ids);
  UPDATE ecosystem_match SET deleted_at=now(),disabled_at=now(),updated_at=now(),version=version+1 WHERE id=ANY(gx_ids);
  UPDATE collaboration_task SET deleted_at=now(),disabled_at=now(),updated_at=now(),version=version+1 WHERE id=ANY(gx_ids);
  UPDATE knowledge_document SET deleted_at=now(),deleted_by_subject='{ACTOR}',status='ARCHIVED',lifecycle_version=lifecycle_version+1 WHERE id=ANY(gx_ids);
  DELETE FROM policy_impact_analysis WHERE id=ANY(gx_ids);
  DELETE FROM notification_message WHERE association_id='{ASSOCIATION}' AND resource_id=ANY(gx_ids);
  DELETE FROM outbox_event WHERE aggregate_id=ANY(gx_ids);
  INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report)
    VALUES('{RUN}','{ASSOCIATION}','{SOURCE_SHA}','北京地下管线协会平台数据(1).zip','{ACTOR}',gx_report);
  INSERT INTO member_import_batch(id,association_id,original_filename,status,total_rows,valid_rows,invalid_rows,
      created_by_subject,committed_at,template_version,source_sha256,submitted_unit)
    VALUES('bddaf1f9-a352-5015-9798-047ad34a13fc','{ASSOCIATION}','北京地下管线协会平台数据(1).zip',
      'COMMITTED',116,116,0,'{ACTOR}',now(),'GX-PLATFORM-DATASET-2026-09','{SOURCE_SHA}','北京地下管线协会');
  gx_count:=2;
  FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_data->'enterprises') LOOP
    gx_id:=(gx_row->>'id')::uuid;
    INSERT INTO enterprise(id,association_id,name,category,address,contact_phone,description,visibility,status)
      VALUES(gx_id,'{ASSOCIATION}',gx_row->'profile'->>'name',gx_row->'profile'->>'category',
        gx_row->'profile'->>'address',gx_row->'profile'->>'contactPhone',gx_row->'profile'->>'intro','ASSOCIATION',
        CASE WHEN gx_row->>'membershipEvidence'='APPLICATION_ONLY' THEN 'PENDING_REVIEW' ELSE 'ACTIVE' END);
    INSERT INTO member_import_row(batch_id,row_number,payload,status,enterprise_id)
      VALUES('bddaf1f9-a352-5015-9798-047ad34a13fc',gx_count,gx_row->'profile','IMPORTED',gx_id);
    INSERT INTO business_entity_history(association_id,enterprise_id,resource_type,resource_id,resource_version,action,actor_subject,snapshot)
      VALUES('{ASSOCIATION}',gx_id,'MEMBER',gx_id,0,'PLATFORM_DATASET_IMPORT','{ACTOR}',gx_row);
    gx_count:=gx_count+1;
  END LOOP;
  FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_data->'policies') LOOP
    INSERT INTO policy_document(id,association_id,title,issuing_authority,document_number,published_on,effective_on,
        source_url,summary,category,policy_level,tags,visibility,status,created_by_subject,updated_by_subject,approved_by_subject,approved_at)
      VALUES((gx_row->>'id')::uuid,'{ASSOCIATION}',gx_row->>'title',gx_row->>'issuingAuthority',gx_row->>'documentNumber',
        (gx_row->>'publishedOn')::date,(gx_row->>'effectiveOn')::date,gx_row->>'sourceUrl',gx_row->>'summary',
        gx_row->>'category',gx_row->>'region',jsonb_build_array(gx_row->'source'->>'涉及领域'),'MEMBERS','PUBLISHED',
        '{ACTOR}','{ACTOR}','{ACTOR}',now());
  END LOOP;
  FOR gx_key,gx_kind IN SELECT * FROM (VALUES ('enterprises','ENTERPRISE'),('policies','POLICY'),
      ('associationDirectory','ASSOCIATION'),('externalOpportunities','TENDER')) kinds LOOP
    FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_data->gx_key) LOOP
      INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,enterprise_id,policy_id,payload)
        VALUES((gx_row->>'recordId')::uuid,'{RUN}','{ASSOCIATION}',gx_kind,gx_row->>'sourceId',
          coalesce(gx_row->>'title',gx_row->>'name',gx_row->'profile'->>'name'),
          CASE WHEN gx_kind='ENTERPRISE' THEN (gx_row->>'id')::uuid END,
          CASE WHEN gx_kind='POLICY' THEN (gx_row->>'id')::uuid END,gx_row);
    END LOOP;
  END LOOP;
  IF (SELECT count(*) FROM platform_source_record WHERE import_id='{RUN}')<>211
      OR (SELECT count(*) FROM enterprise WHERE id IN (SELECT (value->>'id')::uuid FROM jsonb_array_elements(gx_data->'enterprises')))<>116
      OR (SELECT count(*) FROM policy_document WHERE id IN (SELECT (value->>'id')::uuid FROM jsonb_array_elements(gx_data->'policies')))<>40 THEN
    RAISE EXCEPTION 'Import count verification failed'; END IF;
  INSERT INTO audit_log(actor_subject,actor_username,association_id,action,resource_type,resource_id,outcome,details,request_id)
    VALUES('{ACTOR}','admin-maintenance','{ASSOCIATION}','PLATFORM_DATASET_IMPORT','PLATFORM_DATASET','{RUN}',
      'SUCCESS',gx_report,'{RUN}');
END $gx_import$;
COMMIT;
SELECT report::text FROM platform_dataset_import WHERE id='{RUN}';
"""
    return guard + body


def compose() -> list[str]:
    return ["/usr/bin/docker", "compose", "--env-file", "/opt/guanxian-single/deploy.env",
            "-f", "/home/admin/guanxian/compose.single-host.yml", "exec", "-T", "postgres"]


def backup() -> Path:
    directory = Path("/var/backups/guanxian/platform-import-20260907")
    if directory.is_symlink():
        raise SystemExit("Unsafe backup directory")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    target = directory / f"guanxian-before-{stamp}.dump"
    with target.open("xb") as out:
        os.chmod(target, 0o600)
        subprocess.run(compose() + ["pg_dump", "--username", "postgres", "--dbname", "guanxian", "--format=custom",
                                   "--no-owner", "--no-privileges"], stdout=out, check=True, timeout=120)
    if target.stat().st_size == 0:
        raise SystemExit("Empty backup; stopped")
    with target.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    manifest = target.with_suffix(".dump.manifest.json")
    with manifest.open("x", encoding="utf-8") as out:
        os.chmod(manifest, 0o600)
        json.dump({"archive": target.name, "sizeBytes": target.stat().st_size, "sha256": digest}, out)
    print(json.dumps({"backup": str(target), "sha256": digest}), flush=True)
    return directory


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-source-sha256")
    args = parser.parse_args()
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run from the server's admin session using sudo")
    os.umask(0o077)
    bundle = prepare(args.archive.read_bytes(), SOURCE_SHA)
    if args.execute and args.confirm_source_sha256 != SOURCE_SHA:
        raise SystemExit("Explicit exact source checksum confirmation required")
    psql = compose() + ["psql", "--username", "postgres", "--dbname", "guanxian", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--quiet", "--no-align", "--tuples-only"]
    subprocess.run(psql, input=render_sql(bundle), encoding="utf-8", check=True, timeout=130)
    if not args.execute:
        print(json.dumps({"mode": "read-only-preflight", "counts": bundle["counts"], "sourceSha256": SOURCE_SHA}))
        return
    directory = backup()
    completed = subprocess.run(psql, input=render_sql(bundle, execute=True), encoding="utf-8", check=True,
                               timeout=150, stdout=subprocess.PIPE)
    report = directory / f"report-{dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')}.json"
    with report.open("x", encoding="utf-8") as out:
        out.write(completed.stdout)
    print(completed.stdout)
    print(json.dumps({"report": str(report)}))


if __name__ == "__main__":
    main()
