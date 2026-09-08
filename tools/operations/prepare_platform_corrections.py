"""Prepare reviewable corrections for three confirmed source issues; never connects to a database.

Produces a plan, read-only preflight SQL and separately labelled write SQL. The
write script requires an operator-created backup and approval before execution.
Original source dictionaries are retained; changed live records abort the batch.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
from pathlib import Path

from apply_platform_dataset import ASSOCIATION, RUN, SOURCE_SHA, ACTOR as IMPORT_ACTOR, json_sql, mapped_bundle
from prepare_platform_dataset import prepare, safe_link

CORRECTION_ID = "beijing-source-metadata-20260908-v1"
ACTOR = "maintenance:source-metadata-20260908"
STANDARD_EVIDENCE = "https://ebook.chinabuilding.com.cn/zbooklib/bookpdf/probation?SiteID=1&bookID=155860"
PLANNING_EVIDENCE = "https://ghzrzyw.beijing.gov.cn/zhengwuxinxi/zcwj/qtwj/202608/t20260811_4818247.html"
ORIGINAL_NOTICE = "https://ggzyfw.beijing.gov.cn/jyxxggjtbyqs/20260901/5691903.html"
CORRECTION_NOTICE = "https://ggzyfw.beijing.gov.cn/jyxxgcjsgzgg/20260902/5694110.html"
COMBINED_NOTICE = ORIGINAL_NOTICE + "；更正：" + CORRECTION_NOTICE


def policy_state(record: dict, version: int) -> dict:
    return {"id": record["id"], "association_id": ASSOCIATION, "title": record["title"],
            "document_number": record["documentNumber"], "effective_on": record["effectiveOn"],
            "published_on": record["publishedOn"], "source_url": record["sourceUrl"],
            "summary": record["summary"], "issuing_authority": record["issuingAuthority"],
            "category": record["category"], "policy_level": record["region"],
            "tags": [record["source"]["涉及领域"]], "status": "PUBLISHED", "visibility": "MEMBERS",
            "deleted_at": None, "disabled_at": None, "version": version,
            "created_by_subject": IMPORT_ACTOR, "updated_by_subject": IMPORT_ACTOR if version == 0 else ACTOR}


def build_plan(bundle: dict) -> dict:
    if bundle.get("sourceSha256") != SOURCE_SHA:
        raise ValueError("Only the reviewed source archive is supported")
    mapped = mapped_bundle(bundle)
    rules = [
        ("POLICY", "policies", "STD-005", "城市综合管廊工程技术标准（2024年版）",
         {"文号或标准号": "GB/T 50838-2024", "实施日期": "2025-01-01", "版本说明": "替代GB 50838-2015。"},
         {"文号或标准号": "GB/T 50838-2015", "实施日期": "2025-04-01",
          "版本说明": "2024年版为局部修订，并调整名称和编号；不是编号年份为2024的新标准。",
          "日期说明": "2025-04-01为本次局部修订实施日期；发布日期为修订公告日期，不替代原版日期。"},
         "按公开修订公告更正编号与修订日期，不据此确认全部条款现行或对企业适用。", [STANDARD_EVIDENCE]),
        ("POLICY", "policies", "POL-005", "北京市地下空间专项规划（2025年—2035年）",
         {"实施日期": "2026-07-24"},
         {"实施日期": "", "日期说明": "官网仅列成文/发布日期2026-07-24，实施日期栏未填；实施日期待核实。"},
         "取消没有独立证据的实施日期，保留成文/发布日期；不表示文件失效。", [PLANNING_EVIDENCE]),
        ("TENDER", "externalOpportunities", "BID-041", "永定路西里老旧小区市政排水管线改造项目",
         {"原文链接": COMBINED_NOTICE},
         {"原文链接": ORIGINAL_NOTICE, "更正公告": CORRECTION_NOTICE},
         "仅拆分原资料中两条公告链接；本次未取到公告，不确认更正内容、预算、资格或当前可参与性。", []),
    ]
    entries = []
    for kind, collection, source_id, title, expected, fields, reason, evidence in rules:
        found = [r for r in mapped[collection] if r["sourceId"] == source_id]
        if len(found) != 1 or found[0]["title"] != title:
            raise ValueError("Reviewed source identity changed: " + source_id)
        before = found[0]
        if any(before["source"].get(k) != v for k, v in expected.items()):
            raise ValueError("Reviewed source fields changed: " + source_id)
        after = copy.deepcopy(before)
        after["correction"] = {"id": CORRECTION_ID, "checkedOn": "2026-09-08", "fields": fields,
                               "reason": reason, "evidenceUrls": evidence, "applicability": "UNVERIFIED"}
        if kind == "POLICY":
            corrected = before["source"] | fields
            after["documentNumber"] = corrected["文号或标准号"] or None
            after["effectiveOn"] = corrected["实施日期"] or None
            after["summary"] = "\n".join(f"{k}：{corrected[k]}" for k in ("核心要求", "企业需关注", "版本说明", "全文状态") if corrected[k])
        else:
            after["sourceUrl"] = safe_link(ORIGINAL_NOTICE)
            after["sourceLinks"] = [{"kind": "ORIGINAL", "url": safe_link(ORIGINAL_NOTICE)},
                                    {"kind": "CORRECTION", "url": safe_link(CORRECTION_NOTICE)}]
        assert after["source"] == before["source"]
        entries.append({"kind": kind, "sourceId": source_id, "recordId": before["recordId"],
                        "before": before, "after": after,
                        "policyBefore": policy_state(before, 0) if kind == "POLICY" else None,
                        "policyAfter": policy_state(after, 1) if kind == "POLICY" else None})
    return {"schemaVersion": 1, "correctionId": CORRECTION_ID, "sourceSha256": SOURCE_SHA,
            "productionApplied": False, "requiresBackupAndApproval": True, "entries": entries}


def render_sql(plan: dict, *, execute: bool = False) -> str:
    if plan.get("correctionId") != CORRECTION_ID or plan.get("sourceSha256") != SOURCE_SHA or len(plan["entries"]) != 3:
        raise ValueError("Unexpected correction plan")
    lock = "LOCK TABLE association,policy_document,platform_dataset_import,platform_source_record IN SHARE ROW EXCLUSIVE MODE;" if execute else ""
    sql = f"""-- {'WRITE SCRIPT: backup and explicit approval required.' if execute else 'READ-ONLY PREFLIGHT: no data changes.'}
{'BEGIN;' if execute else 'BEGIN READ ONLY;'}
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
{lock}
DO $gx_correction$
DECLARE
  gx_entries jsonb := {json_sql(plan['entries'])};
  gx_entry jsonb; gx_source jsonb; gx_policy jsonb; gx_expected jsonb;
  gx_done integer := 0; gx_is_done boolean;
BEGIN
  IF current_database()<>'guanxian' THEN RAISE EXCEPTION 'Wrong database'; END IF;
  IF NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='31' AND success) THEN
    RAISE EXCEPTION 'Deploy and verify schema V31 first'; END IF;
  IF NOT EXISTS(SELECT 1 FROM association WHERE id='{ASSOCIATION}' AND status='ACTIVE') OR
     NOT EXISTS(SELECT 1 FROM platform_dataset_import WHERE id='{RUN}' AND source_sha256='{SOURCE_SHA}' AND association_id='{ASSOCIATION}') THEN
    RAISE EXCEPTION 'Reviewed import or association not found'; END IF;
  FOR gx_entry IN SELECT value FROM jsonb_array_elements(gx_entries) LOOP
    SELECT to_jsonb(r) INTO gx_source FROM platform_source_record r WHERE id=(gx_entry->>'recordId')::uuid
      AND import_id='{RUN}' AND association_id='{ASSOCIATION}' AND kind=gx_entry->>'kind' AND source_id=gx_entry->>'sourceId';
    IF gx_source IS NULL OR gx_source->>'title' IS DISTINCT FROM gx_entry->'before'->>'title'
       OR gx_source->>'enterprise_id' IS NOT NULL
       OR gx_source->>'policy_id' IS DISTINCT FROM (CASE WHEN gx_entry->>'kind'='POLICY' THEN gx_entry->'before'->>'id' END) THEN
      RAISE EXCEPTION 'Source identity changed: %',gx_entry->>'sourceId'; END IF;
    gx_is_done := gx_source->'payload'=gx_entry->'after';
    IF NOT gx_is_done AND gx_source->'payload' IS DISTINCT FROM gx_entry->'before' THEN
      RAISE EXCEPTION 'Source snapshot changed: %',gx_entry->>'sourceId'; END IF;
    IF gx_entry->>'kind'='POLICY' THEN
      SELECT to_jsonb(p) INTO gx_policy FROM policy_document p WHERE id=(gx_entry->'before'->>'id')::uuid;
      gx_expected := CASE WHEN gx_is_done THEN gx_entry->'policyAfter' ELSE gx_entry->'policyBefore' END;
      IF gx_policy IS NULL OR NOT gx_policy @> gx_expected THEN
        RAISE EXCEPTION 'Live policy changed; manual reconciliation required: %',gx_entry->>'sourceId'; END IF;
    END IF;
    IF gx_is_done THEN gx_done := gx_done+1; END IF;
  END LOOP;
  IF gx_done=3 THEN RAISE NOTICE 'Corrections already applied; no changes'; RETURN; END IF;
  IF gx_done<>0 THEN RAISE EXCEPTION 'Partial correction batch; manual reconciliation required'; END IF;
"""
    if not execute:
        return sql + "RAISE NOTICE 'Correction preflight passed; 3 sources and 2 policies would change';\nEND $gx_correction$;\nROLLBACK;\n"
    return sql + f"""
  FOR gx_entry IN SELECT value FROM jsonb_array_elements(gx_entries) LOOP
    INSERT INTO business_entity_history(association_id,resource_type,resource_id,resource_version,action,actor_subject,snapshot)
      VALUES('{ASSOCIATION}','SOURCE_RECORD',(gx_entry->>'recordId')::uuid,1,'SOURCE_METADATA_CORRECTED','{ACTOR}',
        jsonb_build_object('correctionId','{CORRECTION_ID}','before',gx_entry->'before','after',gx_entry->'after'));
    IF gx_entry->>'kind'='POLICY' THEN
      SELECT to_jsonb(p) INTO gx_policy FROM policy_document p WHERE id=(gx_entry->'before'->>'id')::uuid;
      UPDATE policy_document SET document_number=gx_entry->'after'->>'documentNumber',
        effective_on=(gx_entry->'after'->>'effectiveOn')::date,summary=gx_entry->'after'->>'summary',
        version=version+1,updated_at=now(),updated_by_subject='{ACTOR}' WHERE id=(gx_entry->'before'->>'id')::uuid;
      INSERT INTO business_entity_history(association_id,resource_type,resource_id,resource_version,action,actor_subject,snapshot)
        SELECT '{ASSOCIATION}','POLICY',p.id,p.version,'SOURCE_METADATA_CORRECTED','{ACTOR}',
          jsonb_build_object('correctionId','{CORRECTION_ID}','before',gx_policy,'after',to_jsonb(p))
        FROM policy_document p WHERE p.id=(gx_entry->'before'->>'id')::uuid;
    END IF;
    UPDATE platform_source_record SET payload=gx_entry->'after' WHERE id=(gx_entry->>'recordId')::uuid;
    INSERT INTO audit_log(actor_subject,actor_username,association_id,action,resource_type,resource_id,outcome,details,request_id)
      VALUES('{ACTOR}','admin-maintenance','{ASSOCIATION}','SOURCE_METADATA_CORRECTED','SOURCE_RECORD',
        gx_entry->>'recordId','SUCCESS',jsonb_build_object('correctionId','{CORRECTION_ID}','sourceId',gx_entry->>'sourceId',
        'originalSourcePreserved',true,'correction',gx_entry->'after'->'correction'),'{CORRECTION_ID}');
  END LOOP;
END $gx_correction$;
COMMIT;
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    plan = build_plan(prepare(args.archive.read_bytes(), SOURCE_SHA))
    # Refuse to overwrite an earlier reviewed plan. This CLI never executes SQL.
    os.umask(0o077)
    args.output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    artifacts = {"plan.json": json.dumps(plan, ensure_ascii=False, indent=2) + "\n",
                 "preflight.sql": render_sql(plan), "apply-after-backup-and-approval.sql": render_sql(plan, execute=True)}
    for name, content in artifacts.items():
        with (args.output_dir / name).open("x", encoding="utf-8") as output:
            output.write(content)
    print(json.dumps({"status": "prepared-not-applied", "sources": 3, "policies": 2,
                      "outputDirectory": str(args.output_dir)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
