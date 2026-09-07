"""Validate and prepare source CSVs. Never connects to or changes a database.

Source records are preserved alongside mapped fields. Missing values are not
invented, and directory/opportunity records are not membership/consent grants.
The output is an intermediate import bundle, NOT proof of a production import.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
from urllib.parse import urlsplit
import zipfile

TABLES = {
    "01_企业及产品服务.csv": ("enterprises", "企业ID", "企业名称", 116),
    "02_政策与标准.csv": ("policies", "政策标准ID", "名称", 40),
    "03_关联协会.csv": ("associationDirectory", "协会ID", "协会名称", 5),
    "04_近期招投标需求.csv": ("externalOpportunities", "招标ID", "项目名称", 50),
}
MAX_BYTES = 10 * 1024 * 1024


def normalized_name(value: str) -> str:
    """For collision warnings only; never a license to merge distinct entities."""
    return re.sub(r"\s+", "", value).replace("（", "(").replace("）", ")")


def safe_link(value: str, *, allow_bare: bool = False) -> str | None:
    value = value.strip()
    if not value:
        return None
    # A missing protocol remains a display-only original, not an invented URL.
    if allow_bare and "://" not in value:
        return None
    parsed = urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("Unsafe or invalid source URL")
    if parsed.username or parsed.password or any(c.isspace() for c in value):
        raise ValueError("Credentials/whitespace in source URL")
    return value


def source_rows(blob: bytes) -> dict[str, list[dict[str, str]]]:
    result = {}
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        infos = archive.infolist()
        if len(infos) > 20 or sum(i.file_size for i in infos) > MAX_BYTES:
            raise ValueError("Archive exceeds safe size limits")
        seen_entries = set()
        for info in infos:
            name = info.filename.replace("\\", "/")
            if name in seen_entries or name.startswith("/") or ":" in name or ".." in PurePosixPath(name).parts:
                raise ValueError("Duplicate or unsafe archive entry")
            seen_entries.add(name)
            if info.is_dir():
                continue
            base = PurePosixPath(name).name
            if base not in TABLES:
                if base.lower() == "readme.md":
                    continue  # Source prose is data, never executable instructions.
                raise ValueError("Unexpected archive member")
            if base in result:
                raise ValueError("Duplicate source table")
            rows = list(csv.reader(io.StringIO(archive.read(info).decode("utf-8-sig")), strict=True))
            if not rows or len(set(rows[0])) != len(rows[0]):
                raise ValueError("Missing or duplicate CSV header")
            header = rows[0]
            if any(len(row) != len(header) for row in rows[1:]):
                raise ValueError("CSV row width mismatch")
            result[base] = [dict(zip(header, row)) for row in rows[1:]]
            _, identity, label, expected_count = TABLES[base]
            if identity not in header or label not in header or len(result[base]) != expected_count:
                raise ValueError("Unexpected dataset contract/count")
            ids = [r[identity].strip() for r in result[base]]
            names = [normalized_name(r[label]) for r in result[base]]
            if "" in ids or "" in names or len(set(ids)) != len(ids) or len(set(names)) != len(names):
                raise ValueError("Missing or duplicate source identity")
        if set(result) != set(TABLES):
            raise ValueError("Source table missing")
        if archive.testzip() is not None:
            raise ValueError("Archive CRC mismatch")
    return result


def prepare(blob: bytes, expected_sha256: str) -> dict:
    actual = hashlib.sha256(blob).hexdigest()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256) or actual != expected_sha256:
        raise ValueError("Archive checksum mismatch")
    if len(blob) > MAX_BYTES:
        raise ValueError("Archive exceeds safe size limits")
    tables = source_rows(blob)
    bundle = {
        "schemaVersion": 1,
        "operation": "prepare-platform-dataset",
        "sourceSha256": actual,
        "productionApplied": False,
        "rules": {
            "existingEnterprisePolicy": "fill-empty-only-after-exact-name-and-association-reconciliation",
            "ambiguousEnterprisePolicy": "stop-for-review",
            "missingValues": "leave-empty",
            "accountsAndBindings": "preserve-no-automatic-provisioning",
            "guestPublication": "no-automatic-publication",
            "externalOpportunities": "not-member-published-demands",
            "associationDirectory": "no-sharing-consents-or-relationships",
            "recommendations": "unverified-source-suggestions-not-confirmed-matches",
        },
    }
    orgs = tables["01_企业及产品服务.csv"]
    org_names = {r["企业ID"]: r["企业名称"] for r in orgs}
    enterprises = []
    for r in orgs:
        notes = [r["企业简介"].strip()]
        for field in ("业务领域", "主要产品与服务", "能力与资质", "已知协会关系", "网址"):
            if r[field].strip():
                notes.append(f"{field}（来源资料）：{r[field].strip()}")
        description = "\n".join(n for n in notes if n)
        profile = {
            "name": r["企业名称"].strip(), "category": r["主体类型"].strip(),
            "address": r["地址"].strip() or None,
            "contactPhone": r["联系电话"].strip() or None,
            "contactName": None, "contactEmail": None, "unifiedSocialCreditCode": None,
            "intro": description,
            "capabilities": [], "products": [], "services": [],
            "applicationScenarios": [], "cooperationNeeds": [],
            "visibility": "ASSOCIATION",
        }
        for field, limit in (("name", 200), ("category", 100), ("address", 300), ("contactPhone", 50), ("intro", 2000)):
            if len(profile[field] or "") > limit:
                raise ValueError(f"Member field too long: {field}")
        enterprises.append({
            "sourceId": r["企业ID"], "profile": profile,
            "membershipEvidence": "APPLICATION_ONLY" if "入会申请" in r["已知协会关系"] else "SOURCE_LISTED_NOT_INDEPENDENTLY_VERIFIED",
            "websiteLink": safe_link(r["网址"], allow_bare=True),
            "source": r,
        })
    bundle["enterprises"] = enterprises
    bundle["policies"] = [{
        "sourceId": r["政策标准ID"], "title": r["名称"],
        "issuingAuthority": r["发布机构"] or None,
        "documentNumber": r["文号或标准号"] or None,
        "publishedOn": dt.date.fromisoformat(r["发布日期"]).isoformat() if r["发布日期"] else None,
        "effectiveOn": dt.date.fromisoformat(r["实施日期"]).isoformat() if r["实施日期"] else None,
        "sourceUrl": safe_link(r["官方原文链接"]),
        "category": r["文件类型"], "region": r["适用地区"],
        "summary": "\n".join(f"{k}：{r[k]}" for k in ("核心要求", "企业需关注", "版本说明", "全文状态") if r[k]),
        "fullTextArchived": False, "source": r,
    } for r in tables["02_政策与标准.csv"]]
    for record in bundle["policies"]:
        for field, limit in (("title", 300), ("issuingAuthority", 200), ("documentNumber", 100), ("category", 100), ("region", 64)):
            if len(record[field] or "") > limit:
                raise ValueError(f"Policy field too long: {field}")
    bundle["associationDirectory"] = [{
        "sourceId": r["协会ID"], "name": r["协会名称"],
        "websiteLink": safe_link(r["官网"], allow_bare=True),
        "sharingPermissionGranted": False, "source": r,
    } for r in tables["03_关联协会.csv"]]
    opportunities = []
    for r in tables["04_近期招投标需求.csv"]:
        refs = []
        for ref in r["建议匹配企业（ID）"].split("；"):
            if ref.strip() in ("", "无高置信直接匹配"):
                continue
            match = re.fullmatch(r"\s*(ORG-\d+)\s+(.+?)\s*", ref)
            if not match or org_names.get(match[1]) != match[2]:
                raise ValueError("Unresolved opportunity enterprise reference")
            refs.append(match[1])
        deadline = dt.datetime.fromisoformat(r["截止或开标时间"]).replace(tzinfo=dt.timezone(dt.timedelta(hours=8)))
        opportunities.append({
            "sourceId": r["招标ID"], "title": r["项目名称"],
            "recordType": "EXTERNAL_OPPORTUNITY", "sourceUrl": safe_link(r["原文链接"]),
            "sourceCheckedOn": dt.date.fromisoformat(r["核验日期"]).isoformat(),
            "deadlineAt": deadline.isoformat(), "statusAtSourceCheck": r["当前状态"],
            "suggestedEnterpriseSourceIds": refs, "recommendationsVerified": False,
            "source": r,
        })
    bundle["externalOpportunities"] = opportunities
    for records in (bundle["associationDirectory"], opportunities):
        for record in records:
            if len(record.get("title", record.get("name", ""))) > 300:
                raise ValueError("Source title too long")
    bundle["counts"] = {name: len(bundle[name]) for name, *_ in TABLES.values()}
    return bundle


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    bundle = prepare(args.archive.read_bytes(), args.expected_sha256)
    # Exclusive creation: don't overwrite a previous reviewed bundle.
    with args.output.open("x", encoding="utf-8", newline="\n") as out:
        json.dump(bundle, out, ensure_ascii=False, indent=2)
        out.write("\n")
    print(json.dumps({"status": "prepared-not-imported", "counts": bundle["counts"],
                      "sourceSha256": bundle["sourceSha256"], "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
