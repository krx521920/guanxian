"""Prepare append-only external tender leads. No database/network access or execution option."""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit
import uuid

from apply_platform_dataset import ASSOCIATION, json_sql

ACTOR = "maintenance:curated-tender-leads"
FIELDS = {"来源平台", "公告类型", "采购人或招标人", "项目编号", "项目地区", "采购类型", "发布日期", "预计公告日期",
          "预算或最高限价", "采购内容", "关键要求", "需求标签", "业务关联说明", "文件获取开始时间", "文件获取截止时间",
          "提交截止类型", "截止或开标时间"}
TYPES = {"招标计划", "资格预审", "招标公告", "采购公告"}
CAUTION = "仅核对原公告正文；更正及终止链未穷尽，实际参与和企业资格须另行核实。"


def notice_url(value: str) -> str:
    if not isinstance(value, str) or re.search(r"[\s\\；;]", value):
        raise ValueError("Unsafe notice URL")
    p = urlsplit(value)
    if (p.scheme != "https" or p.username or p.password or p.port or p.query or p.fragment
            or p.hostname not in {"www.ccgp.gov.cn", "ggzyfw.beijing.gov.cn"}):
        raise ValueError("Only reviewed official public notice URLs are supported")
    if not re.fullmatch(r"/(?:cggg/\w+/\w+/\d{6}/t\d{8}_\d+\.htm|jyxx\w+/\d{8}/\d+\.html)", p.path):
        raise ValueError("A notice detail URL is required, not a portal or search page")
    return value


def canonical_url(value: str) -> str:
    return re.sub(r"^https?://(?:www\.)?", "", value.lower()).rstrip("/")


def prepare(blob: bytes, baseline: dict) -> dict:
    if len(blob) > 200_000:
        raise ValueError("Catalog too large")
    catalog = json.loads(blob)
    batch = catalog.get("batchId", "")
    if catalog.get("schemaVersion") != 1 or not re.fullmatch(r"tender-leads-\d{8}(?:-[a-z0-9]+)?", batch):
        raise ValueError("Invalid reviewed catalog identity")
    checked = dt.date.fromisoformat(catalog["checkedOn"])
    if not isinstance(catalog.get("records"), list) or not 1 <= len(catalog["records"]) <= 30:
        raise ValueError("Expected a small manually reviewed catalog")
    existing_urls, existing_projects, existing_titles = set(), set(), set()
    for old in baseline.get("externalOpportunities", []):
        for url in re.findall(r"https?://[^；;\s]+", old.get("sourceUrl") or ""):
            existing_urls.add(canonical_url(url))
        if number := old.get("source", {}).get("项目编号"):
            existing_projects.add(number.strip())
        existing_titles.add(re.sub(r"\s+", "", old.get("title", "")))
    rows, seen_urls, seen_projects, seen_titles = [], set(), set(), set()
    for original in catalog["records"]:
        title, url = original.get("title"), notice_url(original.get("url"))
        source = copy.deepcopy(original.get("source"))
        if not isinstance(title, str) or not title.strip() or len(title) > 300 or not isinstance(source, dict):
            raise ValueError("Invalid notice title/source")
        if set(source) - FIELDS or any(not isinstance(v, str) or len(v) > 1000 for v in source.values()):
            raise ValueError("Unknown or oversized display fields")
        for key in ("公告类型", "项目编号", "项目地区", "发布日期", "采购内容", "需求标签", "业务关联说明"):
            if not source.get(key, "").strip():
                raise ValueError("Missing evidence-backed field: " + key)
        if source["公告类型"] not in TYPES or dt.date.fromisoformat(source["发布日期"]) > checked:
            raise ValueError("Unreviewed type or future publication")
        times = {key: dt.datetime.strptime(source[key], "%Y-%m-%d %H:%M:%S") for key in
                 ("文件获取开始时间", "文件获取截止时间", "截止或开标时间") if source.get(key)}
        if source["公告类型"] == "招标计划":
            if times or source.get("提交截止类型"):
                raise ValueError("A plan is not an open procurement")
            dt.date.fromisoformat(source["预计公告日期"])
        else:
            if len(times) != 3 or not times["文件获取开始时间"] <= times["文件获取截止时间"] <= times["截止或开标时间"]:
                raise ValueError("Missing or contradictory notice deadlines")
            if source.get("提交截止类型") not in {"资格预审申请截止", "响应文件截止", "投标文件截止"}:
                raise ValueError("Submission deadline kind required")
            if (source["公告类型"] == "资格预审") != (source["提交截止类型"] == "资格预审申请截止"):
                raise ValueError("Prequalification must not be presented as bid closing")
            if times["截止或开标时间"].date() < checked:
                raise ValueError("Closed notices are not new leads")
        identity, number = canonical_url(url), source["项目编号"].strip()
        if (identity in existing_urls | seen_urls or number in existing_projects | seen_projects
                or re.sub(r"\s+", "", title) in existing_titles | seen_titles):
            raise ValueError("Duplicate source/project; reconcile instead of inserting: " + number)
        seen_urls.add(identity); seen_projects.add(number)
        seen_titles.add(re.sub(r"\s+", "", title))
        source.update({"原文链接": url, "核验日期": checked.isoformat(), "核验边界": CAUTION})
        rows.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, ASSOCIATION + ":tender:" + identity)),
                     "sourceId": "WEB-" + hashlib.sha256(identity.encode()).hexdigest()[:16],
                     "title": title, "sourceUrl": url, "source": source,
                     "participationEligibility": "UNVERIFIED", "memberDemandCreated": False})
    return {"schemaVersion": 1, "batchId": batch, "sourceSha256": hashlib.sha256(blob).hexdigest(),
            "associationId": ASSOCIATION, "productionApplied": False, "records": rows}


def render_sql(plan: dict, *, execute: bool = False) -> str:
    # Values are encoded JSON data, never SQL fragments from scraped documents.
    header = "BEGIN;" if execute else "BEGIN READ ONLY;"
    lock = "LOCK TABLE association,platform_dataset_import,platform_source_record IN SHARE ROW EXCLUSIVE MODE;" if execute else ""
    sql = f"""-- {'WRITE: backup and reviewed batch approval required' if execute else 'READ ONLY: no writes'}
{header}
SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s';
{lock}
DO $gx_leads$
DECLARE gx_plan jsonb := {json_sql(plan)}; gx_row jsonb; gx_old jsonb; gx_run jsonb;
BEGIN
  IF current_database()<>'guanxian' OR NOT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='30' AND success)
      OR NOT EXISTS(SELECT 1 FROM association WHERE id='{ASSOCIATION}' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'Unexpected database, schema or association'; END IF;
  SELECT to_jsonb(i) INTO gx_run FROM platform_dataset_import i WHERE id=gx_plan->>'batchId';
  IF gx_run IS NOT NULL THEN
    IF gx_run->>'source_sha256' IS DISTINCT FROM gx_plan->>'sourceSha256' OR gx_run->>'association_id'<>'{ASSOCIATION}'
        OR (SELECT count(*) FROM platform_source_record WHERE import_id=gx_plan->>'batchId')<>jsonb_array_length(gx_plan->'records') THEN
      RAISE EXCEPTION 'Changed or partial batch'; END IF;
    FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_plan->'records') LOOP
      IF NOT EXISTS(SELECT 1 FROM platform_source_record WHERE id=(gx_row->>'id')::uuid AND import_id=gx_plan->>'batchId'
          AND association_id='{ASSOCIATION}' AND kind='TENDER' AND source_id=gx_row->>'sourceId'
          AND title=gx_row->>'title' AND enterprise_id IS NULL AND policy_id IS NULL AND payload=gx_row) THEN
        RAISE EXCEPTION 'Existing batch was modified'; END IF;
    END LOOP;
    RAISE NOTICE 'Already imported; no changes'; RETURN;
  END IF;
  IF EXISTS(SELECT 1 FROM platform_dataset_import WHERE association_id='{ASSOCIATION}' AND source_sha256=gx_plan->>'sourceSha256') THEN
    RAISE EXCEPTION 'Source already imported under another batch'; END IF;
  FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_plan->'records') LOOP
    IF EXISTS(SELECT 1 FROM platform_source_record r WHERE r.id=(gx_row->>'id')::uuid OR
      (r.association_id='{ASSOCIATION}' AND r.kind='TENDER' AND (
        gx_row->'source'->>'项目编号' IN (r.payload->'source'->>'项目编号',
          r.payload->'correction'->'fields'->>'项目编号',
          r.payload->'publicEvidence'->'fields'->>'项目编号',
          r.payload->'publicEvidence'->'record'->>'projectNumber')
        OR regexp_replace(r.title,'\\s','','g')=regexp_replace(gx_row->>'title','\\s','','g')
        OR position(regexp_replace(lower(gx_row->>'sourceUrl'),'^https?://(www[.])?','') in
          regexp_replace(lower(concat_ws(' ',r.payload->>'sourceUrl',r.payload->'source'->>'原文链接',
            r.payload->'correction'->'fields'->>'原文链接',r.payload->>'sourceLinks',
            r.payload->'publicEvidence'->'record'->>'sourceUrl',
            r.payload->'publicEvidence'->'fields'->>'原文链接',
            r.payload->'publicEvidence'->>'supportingUrls',
            r.payload->'publicEvidence'->'record'->>'supportingUrls')),'https?://(www[.])?','','g'))>0))) THEN
      RAISE EXCEPTION 'Existing notice/project collision: %',gx_row->>'sourceId'; END IF;
  END LOOP;
"""
    if not execute:
        return sql + "RAISE NOTICE 'Append-only tender lead preflight passed'; END $gx_leads$;\nROLLBACK;\n"
    return sql + f"""
  INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report)
    VALUES(gx_plan->>'batchId','{ASSOCIATION}',gx_plan->>'sourceSha256',(gx_plan->>'batchId')||'.json','{ACTOR}',
      jsonb_build_object('operation','append-tender-leads','count',jsonb_array_length(gx_plan->'records'),'memberDemandCreated',false));
  FOR gx_row IN SELECT value FROM jsonb_array_elements(gx_plan->'records') LOOP
    INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
      VALUES((gx_row->>'id')::uuid,gx_plan->>'batchId','{ASSOCIATION}','TENDER',gx_row->>'sourceId',gx_row->>'title',gx_row);
    INSERT INTO audit_log(actor_subject,actor_username,association_id,action,resource_type,resource_id,outcome,details,request_id)
      VALUES('{ACTOR}','admin-maintenance','{ASSOCIATION}','IMPORT_EXTERNAL_TENDER','SOURCE_RECORD',gx_row->>'id','SUCCESS',
        jsonb_build_object('batch',gx_plan->>'batchId','sourceUrl',gx_row->>'sourceUrl','eligibility','UNVERIFIED'),gx_plan->>'batchId');
  END LOOP;
END $gx_leads$;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True, help="Previously prepared source bundle, read only")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    plan = prepare(args.catalog.read_bytes(), json.loads(args.baseline.read_text(encoding="utf-8")))
    os.umask(0o077)
    args.output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name, content in {"plan.json": json.dumps(plan, ensure_ascii=False, indent=2),
                          "preflight.sql": render_sql(plan), "apply-after-backup-and-approval.sql": render_sql(plan, execute=True)}.items():
        with (args.output_dir / name).open("x", encoding="utf-8") as out:
            out.write(content + ("\n" if name.endswith(".json") else ""))
    print(json.dumps({"status": "prepared-not-applied", "records": len(plan["records"]), "sha256": plan["sourceSha256"]}))


if __name__ == "__main__":
    main()
