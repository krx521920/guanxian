"""Prepare reviewed annual evidence; root-only production apply after V30.1 and backup.

No scraping, migration, deployment, account changes, consent grants or member business writes.
The default only prepares a plan; --preflight-server is read-only; --execute is explicit.
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

from validate_member_public_evidence import validate

ASSOCIATION = "00000000-0000-0000-0000-000000000106"
BATCH = "member-annual-evidence-20260908"
CATALOG_SHA = "82973b3bf348b578bd707017ffc51bc5e3a6e907847bb9c9d6defb66d5560e30"
BASELINE_SHA = "f9c1243ac018e6a323c18f212ecac7f068ac09f84ce3d8fdcae60c250c370e24"
BASE_IMPORT = "beijing-platform-20260907"
BASE_SOURCE_SHA = "03b0929d31d163f51461eb7f8011b14c356c02d4f7ccb5bdf2040103920f9daa"
ACTOR = "maintenance:member-annual-evidence"
BOUNDARY = "公开来源记录，不是会员自行发布或平台确认的合作；企业资格、会员关系和后续变化未据此认定。"
CLASSES = {"PRIMARY_NOTICE": "采购/交易平台公告", "ISSUER_DISCLOSURE": "上市公司披露（非最终合同核验）",
           "PRIMARY_PARTICIPANT_NEWS": "参与方报道", "SELF_REPORTED_NEWS": "企业自述",
           "ATTRIBUTED_COMPANY_REPRINT": "署名企业转载", "ORGANIZER_REPORT": "主办方报道",
           "ASSOCIATION_REPORT": "协会报道", "ENTERPRISE_SELF_REPORT": "企业自述"}
AMOUNTS = {"AWARD": "中标/成交金额", "CONTRACT": "公告合同金额", "BID_QUOTE": "投标报价（非中标金额）",
           "ESTIMATED_CONTRACT": "估算合同额", "ESTIMATED_PROCUREMENT": "采购估算额",
           "CONSORTIUM_TOTAL_AWARD": "联合体中标总额（非单家企业金额）"}


def json_sql(value):
    encoded = base64.b64encode(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()).decode()
    return "convert_from(decode('" + encoded + "','base64'),'UTF8')::jsonb"


def display_fields(row):
    fields = {"发布日期": row["publishedOn"], "核验日期": row["checkedOn"], "记录状态": row["statusLabel"],
              "记录状态代码": row["status"], "证据类型": CLASSES[row["evidenceClass"]],
              "证据摘要": row["summary"], "原文链接": row["sourceUrl"], "核验边界": BOUNDARY,
              "关联企业及角色": "\n".join(f"{r['enterpriseName']}（{r['sourceId']}）：{r['roleLabel']}"
                                           for r in row["enterpriseRelations"])}
    for source, target in (("projectNumber", "项目编号"), ("region", "项目地区"), ("buyer", "采购人或招标人"),
                           ("noticeType", "公告类型")):
        if row.get(source): fields[target] = row[source]
    if row.get("amount"):
        amount = row["amount"]
        fields["金额及口径"] = f"{amount['value']} 元；{AMOUNTS[amount['basis']]}"
    if row.get("lots"):
        fields["分标段信息"] = "\n".join(f"{lot['lot']}：{lot['sourceId']}，中标费率 {lot['awardRatePercent']}%（非金额）"
                                        for lot in row["lots"])
    if row.get("enterpriseShare"):
        share = row["enterpriseShare"]
        fields["披露份额"] = (f"{share['sourceId']}：披露工作量占比 {share['workloadPercent']}%，"
                          f"金额约 {share['amountApproxYuan']} 元；发行人估计，非最终合同核验")
    if row.get("latestOutcome"):
        fields["后续结果"] = {"FINAL_AWARD_NOT_VERIFIED": "最终中标结果未核实",
                           "FINAL_AWARD_VERIFIED_OTHER_SUPPLIER": "已核对后续结果：其他供应商中标"}[row["latestOutcome"]]
    if row.get("occurredOn"): fields["活动时间"] = row["occurredOn"]
    elif row.get("occurredPeriod"):
        period = row["occurredPeriod"]
        fields["活动时间"] = f"{period['from']} 至 {period['through']}" if isinstance(period, dict) else period
    elif row.get("startsOn"):
        fields["活动时间"] = row["startsOn"] + (" 至 " + row["endsOn"] if row.get("endsOn") else "") + "（公告计划）"
    if row.get("submissionEnd"):
        fields["截止或开标时间"] = dt.datetime.fromisoformat(row["submissionEnd"]).strftime("%Y-%m-%d %H:%M:%S")
    return fields


def prepare(catalog_bytes, baseline_bytes):
    if hashlib.sha256(catalog_bytes).hexdigest() != CATALOG_SHA:
        raise ValueError("Reviewed catalog checksum mismatch")
    if hashlib.sha256(baseline_bytes).hexdigest() != BASELINE_SHA:
        raise ValueError("Reviewed baseline checksum mismatch")
    catalog, baseline = json.loads(catalog_bytes), json.loads(baseline_bytes)
    checked = validate(catalog, baseline)
    if checked["records"] != 64 or checked["heldForReview"] != 17 or checked["existingProjectEnrichments"] != 1:
        raise ValueError("Unexpected reviewed counts")
    identities = {r["sourceId"]: r for row in catalog["records"] for r in row["enterpriseRelations"]}
    rows = []
    for row in catalog["records"]:
        previous = row.get("existingSourceIds", [])
        if previous and previous != ["BID-049"]:
            raise ValueError("Unreviewed existing target")
        evidence = {"batchId": BATCH, "record": row, "fields": display_fields(row),
                    "supportingUrls": row.get("supportingUrls", [])}
        rows.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, ASSOCIATION + ":public-evidence:" + row["sourceUrl"])),
                     "sourceId": row["id"], "kind": row["category"], "title": row["title"],
                     "existingSourceId": previous[0] if previous else None,
                     "payload": {"source": evidence["fields"], "sourceUrl": row["sourceUrl"], "publicEvidence": evidence}})
    return {"schemaVersion": 1, "batchId": BATCH, "sourceSha256": CATALOG_SHA, "baselineSha256": BASELINE_SHA,
            "associationId": ASSOCIATION, "checkedOn": catalog["checkedOn"], "window": catalog["window"],
            "counts": {"records": 64, "insert": 63, "enrich": 1, "tender": 41, "activity": 23,
                       "enterpriseIdentities": len(identities), "roleLinks": sum(len(r["enterpriseRelations"]) for r in catalog["records"]),
                       "heldExcluded": 17},
            "identities": [{k: r[k] for k in ("sourceId", "enterpriseName", "membershipEvidence")}
                           for r in sorted(identities.values(), key=lambda r: r["sourceId"])],
            "records": rows}


def render_sql(plan, *, execute=False, rollback=False):
    """JSON values are data, not interpolated SQL. Revalidate live identities under locks."""
    begin = "BEGIN;" if execute else "BEGIN READ ONLY;"
    lock = "LOCK TABLE association,enterprise,platform_dataset_import,platform_source_record IN SHARE ROW EXCLUSIVE MODE;" if execute else ""
    existing = "IF batch IS NOT NULL THEN RAISE NOTICE 'Already imported and unchanged; no writes'; RETURN; END IF;"
    if rollback:
        existing = "IF batch IS NULL THEN RAISE EXCEPTION 'STOP: no applied batch to roll back'; END IF;"
    sql = f"""{begin}
SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='60s';
{lock}
DO $gx_annual$
DECLARE plan jsonb := {json_sql(plan)}; item jsonb; ident jsonb; previous jsonb; batch jsonb;
        target uuid; expected jsonb; targets jsonb := '[]'; snapshots jsonb := '{{}}';
BEGIN
  IF current_database()<>'guanxian' OR NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='30.1' AND success)
      OR NOT EXISTS(SELECT 1 FROM association WHERE id='{ASSOCIATION}' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'STOP: expected guanxian database, active association and deployed V30.1'; END IF;
  IF NOT EXISTS(SELECT 1 FROM platform_dataset_import WHERE id='{BASE_IMPORT}' AND association_id='{ASSOCIATION}'
      AND source_sha256='{BASE_SOURCE_SHA}') THEN RAISE EXCEPTION 'STOP: reviewed base import missing'; END IF;
  FOR ident IN SELECT value FROM jsonb_array_elements(plan->'identities') LOOP
    IF (SELECT count(*) FROM platform_source_record r JOIN enterprise e ON e.id=r.enterprise_id
        AND e.association_id=r.association_id WHERE r.import_id='{BASE_IMPORT}' AND r.association_id='{ASSOCIATION}'
        AND r.kind='ENTERPRISE' AND r.source_id=ident->>'sourceId' AND e.name=ident->>'enterpriseName'
        AND e.deleted_at IS NULL AND e.status NOT IN ('DISABLED','DELETED')
        AND r.payload->>'membershipEvidence'=ident->>'membershipEvidence')<>1 THEN
      RAISE EXCEPTION 'STOP: enterprise identity, membership evidence or visibility changed: %',ident->>'sourceId'; END IF;
  END LOOP;
  SELECT to_jsonb(i) INTO batch FROM platform_dataset_import i WHERE id=plan->>'batchId';
  IF batch IS NOT NULL AND (batch->>'source_sha256' IS DISTINCT FROM plan->>'sourceSha256'
      OR batch->>'association_id'<>'{ASSOCIATION}' OR batch->'report'->'plan' IS DISTINCT FROM plan
      OR (SELECT count(*) FROM platform_source_record WHERE import_id=plan->>'batchId')<>63) THEN
    RAISE EXCEPTION 'STOP: changed or partial import batch'; END IF;
  IF batch IS NULL AND EXISTS(SELECT 1 FROM platform_dataset_import WHERE association_id='{ASSOCIATION}'
      AND source_sha256=plan->>'sourceSha256') THEN RAISE EXCEPTION 'STOP: catalog imported under another batch'; END IF;
  FOR item IN SELECT value FROM jsonb_array_elements(plan->'records') LOOP
    target := (item->>'id')::uuid; previous := NULL;
    IF item->>'existingSourceId' IS NOT NULL THEN
      SELECT to_jsonb(r) INTO previous FROM platform_source_record r WHERE r.import_id='{BASE_IMPORT}'
        AND r.association_id='{ASSOCIATION}' AND r.kind='TENDER' AND r.source_id=item->>'existingSourceId';
      IF previous IS NULL OR previous->'payload'->'source'->>'项目编号' IS DISTINCT FROM item->'payload'->'source'->>'项目编号'
          OR previous->>'enterprise_id' IS NOT NULL OR previous->>'policy_id' IS NOT NULL THEN
        RAISE EXCEPTION 'STOP: original project target changed'; END IF;
      target := (previous->>'id')::uuid;
      IF batch IS NULL AND previous->'payload' ? 'publicEvidence' THEN
        RAISE EXCEPTION 'STOP: existing evidence needs manual reconciliation'; END IF;
    END IF;
    IF batch IS NOT NULL THEN
      IF item->>'existingSourceId' IS NULL THEN
        IF NOT EXISTS(SELECT 1 FROM platform_source_record r WHERE r.id=target AND r.import_id=plan->>'batchId'
            AND r.association_id='{ASSOCIATION}' AND r.kind=item->>'kind' AND r.source_id=item->>'sourceId'
            AND r.title=item->>'title' AND r.enterprise_id IS NULL AND r.policy_id IS NULL AND r.payload=item->'payload') THEN
          RAISE EXCEPTION 'STOP: imported row modified'; END IF;
      ELSE
        expected := batch->'report'->'before'->(target::text);
        IF expected IS NULL OR (previous-'payload') IS DISTINCT FROM (expected-'payload')
            OR previous->'payload' IS DISTINCT FROM (expected->'payload' || jsonb_build_object('publicEvidence',item->'payload'->'publicEvidence')) THEN
          RAISE EXCEPTION 'STOP: enriched row modified'; END IF;
      END IF;
    ELSE
      IF EXISTS(SELECT 1 FROM platform_source_record r WHERE
          (r.id=target AND item->>'existingSourceId' IS NULL) OR
          (r.association_id='{ASSOCIATION}' AND r.kind IN ('TENDER','ACTIVITY') AND r.id<>target AND (
            (item->'payload'->'source'->>'项目编号' IS NOT NULL AND r.payload->'source'->>'项目编号'=item->'payload'->'source'->>'项目编号')
            OR regexp_replace(r.title,'\\s','','g')=regexp_replace(item->>'title','\\s','','g')
            OR position(regexp_replace(item->'payload'->>'sourceUrl','^https?://(www[.])?','') in
              regexp_replace(concat_ws(' ',r.payload->>'sourceUrl',r.payload->'source'->>'原文链接',
                r.payload->'publicEvidence'->'record'->>'sourceUrl'), 'https?://(www[.])?','','g'))>0))) THEN
        RAISE EXCEPTION 'STOP: duplicate source/project: %',item->>'sourceId'; END IF;
    END IF;
    targets := targets || jsonb_build_array(jsonb_build_object('id',target,'item',item));
    IF previous IS NOT NULL THEN snapshots := snapshots || jsonb_build_object(target::text,previous); END IF;
  END LOOP;
  {existing}
"""
    if not execute:
        return sql + "RAISE NOTICE 'Read-only annual evidence preflight passed: 63 new, 1 enriched, 17 excluded'; END $gx_annual$;\nROLLBACK;\n"
    if rollback:
        return sql + f"""
  -- All rows were compared against the immutable original plan above; stop if anyone edited them.
  FOR ident IN SELECT value FROM jsonb_array_elements(targets) LOOP
    target := (ident->>'id')::uuid; item := ident->'item';
    IF item->>'existingSourceId' IS NULL THEN
      DELETE FROM platform_source_record WHERE id=target AND import_id='{BATCH}';
    ELSE
      UPDATE platform_source_record SET payload=batch->'report'->'before'->(target::text)->'payload' WHERE id=target;
    END IF;
  END LOOP;
  DELETE FROM platform_dataset_import WHERE id='{BATCH}';
  INSERT INTO audit_log(actor_subject,actor_username,association_id,action,resource_type,resource_id,outcome,details,request_id)
    VALUES('{ACTOR}','admin-maintenance','{ASSOCIATION}','ROLLBACK_PUBLIC_EVIDENCE','SOURCE_BATCH','{BATCH}','SUCCESS',
      jsonb_build_object('sourceSha256','{CATALOG_SHA}','removedSourceRows',63,'restoredSourceRows',1),'{BATCH}');
END $gx_annual$;
COMMIT;
"""
    return sql + f"""
  INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report)
    VALUES(plan->>'batchId','{ASSOCIATION}',plan->>'sourceSha256','member-annual-evidence-20260908.json','{ACTOR}',
      jsonb_build_object('operation','import-member-annual-evidence','plan',plan,'before',snapshots,
        'accountsChanged',false,'memberBusinessChanged',false,'publicationsGranted',false));
  FOR ident IN SELECT value FROM jsonb_array_elements(targets) LOOP
    target := (ident->>'id')::uuid; item := ident->'item';
    IF item->>'existingSourceId' IS NULL THEN
      INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
        VALUES(target,plan->>'batchId','{ASSOCIATION}',item->>'kind',item->>'sourceId',item->>'title',item->'payload');
    ELSE
      UPDATE platform_source_record SET payload=payload || jsonb_build_object('publicEvidence',item->'payload'->'publicEvidence')
        WHERE id=target;
    END IF;
    INSERT INTO audit_log(actor_subject,actor_username,association_id,action,resource_type,resource_id,outcome,details,request_id)
      VALUES('{ACTOR}','admin-maintenance','{ASSOCIATION}','IMPORT_PUBLIC_EVIDENCE','SOURCE_RECORD',target::text,'SUCCESS',
        jsonb_build_object('batch',plan->>'batchId','recordId',item->>'sourceId','category',item->>'kind',
          'sourceUrl',item->'payload'->>'sourceUrl','enrichment',item->>'existingSourceId' IS NOT NULL),plan->>'batchId');
  END LOOP;
  IF (SELECT count(*) FROM platform_source_record WHERE import_id='{BATCH}')<>63 OR
      (SELECT count(*) FROM platform_source_record WHERE payload->'publicEvidence'->>'batchId'='{BATCH}')<>64 THEN
    RAISE EXCEPTION 'STOP: unexpected written counts'; END IF;
END $gx_annual$;
SELECT jsonb_build_object('status','verified','batchId',id,'counts',report->'plan'->'counts',
  'storedRows',(SELECT count(*) FROM platform_source_record WHERE import_id='{BATCH}'),
  'evidenceRows',(SELECT count(*) FROM platform_source_record WHERE payload->'publicEvidence'->>'batchId'='{BATCH}'),
  'sourceSha256',source_sha256,'accountsChanged',false,'memberBusinessChanged',false,'publicationsGranted',false)
FROM platform_dataset_import WHERE id='{BATCH}';
COMMIT;
"""


def compose():
    return ["/usr/bin/docker", "compose", "--env-file", "/opt/guanxian-single/deploy.env",
            "-f", "/home/admin/guanxian/compose.single-host.yml", "exec", "-T", "postgres"]


def protected_directory():
    path = Path("/var/backups/guanxian/member-annual-evidence-20260908")
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise SystemExit("STOP: unexpected symlink in backup path")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    return path


def backup(directory):
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    archive = directory / f"guanxian-before-{stamp}.dump"
    with archive.open("xb") as out:
        subprocess.run(compose() + ["pg_dump", "-U", "postgres", "-d", "guanxian", "-Fc", "--no-owner", "--no-privileges"],
                       stdout=out, check=True, timeout=180)
    if archive.stat().st_size == 0: raise SystemExit("STOP: empty backup")
    # Verify archive readability in the same PostgreSQL client version, without restoring anywhere.
    with archive.open("rb") as source:
        subprocess.run(compose() + ["pg_restore", "--list"], stdin=source, stdout=subprocess.DEVNULL, check=True, timeout=60)
    with archive.open("rb") as source: digest = hashlib.file_digest(source, "sha256").hexdigest()
    manifest = {"archive": str(archive), "sha256": digest, "sizeBytes": archive.stat().st_size}
    with archive.with_suffix(".dump.manifest.json").open("x", encoding="utf-8") as out:
        json.dump(manifest, out)
    print(json.dumps({"backup": manifest}), flush=True)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--output-dir", type=Path)
    mode.add_argument("--preflight-server", action="store_true")
    mode.add_argument("--execute", action="store_true")
    parser.add_argument("--confirm-source-sha256")
    args = parser.parse_args()
    plan = prepare(args.catalog.read_bytes(), args.baseline.read_bytes())
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=False)
        for name, content in {"plan.json": json.dumps(plan, ensure_ascii=False, indent=2),
                              "preflight.sql": render_sql(plan), "apply.sql": render_sql(plan, execute=True),
                              "rollback-after-review.sql": render_sql(plan, execute=True, rollback=True)}.items():
            with (args.output_dir / name).open("x", encoding="utf-8") as out: out.write(content)
    if not args.preflight_server and not args.execute:
        print(json.dumps({"status": "prepared-not-imported", "counts": plan["counts"], "sha256": CATALOG_SHA}))
        return
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run only from the server admin session using sudo")
    if args.execute and args.confirm_source_sha256 != CATALOG_SHA:
        raise SystemExit("Exact reviewed catalog checksum confirmation required")
    os.umask(0o077)
    # Same maintenance lock prevents a concurrent application restart during the data transaction.
    import fcntl
    with open("/run/lock/guanxian-maintain.lock", "a") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        psql = compose() + ["psql", "-U", "postgres", "-d", "guanxian", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"]
        subprocess.run(psql, input=render_sql(plan), encoding="utf-8", check=True, timeout=180)
        if not args.execute:
            print(json.dumps({"status": "preflight-passed-no-writes", "counts": plan["counts"]}))
            return
        directory = protected_directory()
        before = backup(directory)
        result = subprocess.run(psql, input=render_sql(plan, execute=True), encoding="utf-8",
                                capture_output=True, check=True, timeout=180)
        # Do not report success on a missing/partial result.
        report = json.loads(result.stdout.strip())
        if report.get("storedRows") != 63 or report.get("evidenceRows") != 64:
            raise SystemExit("STOP: post-import counts unexpected; preserve backup and investigate")
        report["backup"] = before
        report_path = directory / ("report-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
        with report_path.open("x", encoding="utf-8") as out: json.dump(report, out, ensure_ascii=False, indent=2)
        print(json.dumps({**report, "report": str(report_path)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
