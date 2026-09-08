"""Validate reviewed public evidence. Read-only; no SQL, network, imports or deployment."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from collections import Counter
from decimal import Decimal
from pathlib import Path
import re
from urllib.parse import urlsplit

HOSTS = {"www.ccgp.gov.cn", "ggzyfw.beijing.gov.cn", "fgw.beijing.gov.cn",
         "www.365trade.com.cn", "365trade.com.cn", "www.chnenergybidding.com.cn",
         "news.pku.edu.cn", "www.csgpc.org", "www.bjsrqxh.cn", "gasheat.com.cn",
         "www.cy-tech.net", "chuanganqi.gkzhan.com"}
ROLES = {"BUYER", "AWARDED_SUPPLIER", "CONTRACT_SUPPLIER", "SHORTLISTED_CANDIDATE",
         "BID_PARTICIPANT", "SIGNATORY", "ORGANIZER", "CO_ORGANIZER", "REPORTED_SUBJECT", "PROMOTER"}
TENDER_STATES = {"AWARDED", "CONTRACT_PUBLISHED", "CANDIDATE_NOTICE", "PARTICIPATION_RECORDED",
                 "SUBMISSION_WINDOW_ENDED", "TERMINATED"}
ACTIVITY_STATES = {"COOPERATION_SIGNED", "HELD", "PLANNED", "CALL_ANNOUNCED",
                   "SELF_REPORTED_ACHIEVEMENT", "PROMOTION_PUBLISHED"}
FALSE_FLAGS = ("memberAuthored", "memberDemandCreated", "confirmedPlatformCollaborationCreated",
               "publicPublicationGranted")
ANNUAL_HOSTS = HOSTS | {"cg.ccteg.cn", "thzb.crsc.cn", "www.hnhxzx.com", "www.hlxzhjy.com",
    "www.bjsx.cn", "csglw.beijing.gov.cn", "www.egova.com.cn", "cuwa.org.cn",
    "www.gasheat.com.cn", "kingfore.net", "jtw.beijing.gov.cn", "www.cntcitc.com.cn",
    "pur.airchina.com.cn"}
ANNUAL_ROLES = ROLES | {"PARTICIPANT", "CONSORTIUM_AWARDED_MEMBER",
    "CONSORTIUM_BID_PARTICIPANT", "DISCLOSING_PARENT"}
ACTIVITY_CLASSES = {"PRIMARY_PARTICIPANT_NEWS", "SELF_REPORTED_NEWS", "ATTRIBUTED_COMPANY_REPRINT",
    "ORGANIZER_REPORT", "ASSOCIATION_REPORT", "ENTERPRISE_SELF_REPORT"}
WINNER_ROLES = {"AWARDED_SUPPLIER", "CONTRACT_SUPPLIER", "CONSORTIUM_AWARDED_MEMBER"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def notice_url(value, hosts=HOSTS):
    require(isinstance(value, str) and not re.search(r"[\s\\\x00-\x1f\x7f]", value), "Unsafe URL")
    p = urlsplit(value)
    require(p.scheme == "https" and p.hostname in hosts and not p.username and not p.password
            and not p.port and not p.fragment and p.path not in {"", "/"}, "Unreviewed source URL")
    return p.hostname.removeprefix("www.") + p.path + ("?" + p.query if p.query else "")


def validate(catalog, baseline):
    require(catalog.get("schemaVersion") == 1 and catalog.get("status") == "REVIEWED_NOT_APPLIED",
            "Not a reviewed evidence catalog")
    require(catalog.get("productionApplied") is False, "Must not claim production import")
    annual = catalog.get("batchId") == "member-annual-evidence-20260908"
    require(annual or catalog.get("batchId") == "member-public-evidence-20260908", "Unexpected reviewed batch")
    hosts, roles = (ANNUAL_HOSTS, ANNUAL_ROLES) if annual else (HOSTS, ROLES)
    start = dt.date.fromisoformat(catalog["window"]["publicationFrom"])
    end = dt.date.fromisoformat(catalog["window"]["publicationThrough"])
    checked = dt.date.fromisoformat(catalog["checkedOn"])
    require(start <= end <= checked, "Invalid publication window")
    if annual:
        require((str(start), str(end), str(checked)) == ("2025-09-08", "2026-09-08", "2026-09-08"),
                "Unexpected annual review window")
    enterprises = {e["sourceId"]: e for e in baseline["enterprises"]}
    require(len(enterprises) == catalog["enterpriseBaseline"]["count"] == 116, "Unexpected baseline")
    rows = catalog.get("records")
    require(isinstance(rows, list) and 1 <= len(rows) <= 200, "Invalid evidence rows")
    seen_ids, seen_urls, seen_projects, orgs = set(), set(), set(), set()
    for row in rows:
        require(row.get("category") in {"TENDER", "ACTIVITY"}, "Unsupported evidence category")
        require(all(row.get(flag) is False for flag in FALSE_FLAGS), "Evidence cannot grant consent or create member actions")
        require(isinstance(row.get("title"), str) and 0 < len(row["title"]) <= 300, "Invalid title")
        require(isinstance(row.get("summary"), str) and 0 < len(row["summary"]) <= 1000, "Missing concise evidence summary")
        require(start <= dt.date.fromisoformat(row["publishedOn"]) <= end, "Publication outside reviewed window")
        require(row.get("checkedOn") == catalog["checkedOn"], "Mismatched verification date")
        require(row["id"] not in seen_ids, "Duplicate evidence id")
        seen_ids.add(row["id"])
        url = notice_url(row["sourceUrl"], hosts)
        require(url not in seen_urls, "Duplicate source notice")
        seen_urls.add(url)
        for link in row.get("supportingUrls", []):
            notice_url(link, hosts)
        require(bool(row.get("enterpriseRelations")), "Missing enterprise role evidence")
        local_orgs, local_roles = set(), set()
        for relation in row["enterpriseRelations"]:
            org = relation["sourceId"]
            require(org in enterprises and org not in local_orgs, "Unknown or duplicated enterprise")
            old = enterprises[org]
            require(relation["enterpriseName"] == old["profile"]["name"], "Enterprise identity mismatch")
            require(relation["membershipEvidence"] == old["membershipEvidence"], "Cannot upgrade membership evidence")
            require(relation["role"] in roles and relation.get("participationEligibility") == "NOT_ASSESSED",
                    "Invalid role or unverified eligibility upgrade")
            local_orgs.add(org); orgs.add(org); local_roles.add(relation["role"])
        state = row["status"]
        if row["category"] == "TENDER":
            require(state in TENDER_STATES or (annual and state == "AWARD_DISCLOSED"), "Activity cannot be a tender")
            disclosure = annual and state == "AWARD_DISCLOSED"
            require(row.get("evidenceClass") == ("ISSUER_DISCLOSURE" if disclosure else "PRIMARY_NOTICE"),
                    "Tender needs primary notice or explicitly identified issuer disclosure")
            if disclosure:
                require("DISCLOSING_PARENT" in local_roles and "CONSORTIUM_AWARDED_MEMBER" in local_roles
                        and row.get("signedFinalContractVerified") is False, "Disclosure is not a signed final contract")
            require(row.get("projectNumber") and row["projectNumber"] not in seen_projects, "Missing/duplicate project number")
            seen_projects.add(row["projectNumber"])
            require(not local_roles & {"ORGANIZER", "CO_ORGANIZER", "SIGNATORY", "PROMOTER", "PARTICIPANT"}, "Activity role in tender")
            if state in {"CANDIDATE_NOTICE", "PARTICIPATION_RECORDED"}:
                require(not local_roles & WINNER_ROLES, "Candidate is not winner")
                verified_other = annual and state == "PARTICIPATION_RECORDED" and row.get("latestOutcome") == "FINAL_AWARD_VERIFIED_OTHER_SUPPLIER"
                require(verified_other or row.get("latestOutcome") == "FINAL_AWARD_NOT_VERIFIED", "Final result boundary missing")
                if verified_other:
                    require(bool(row.get("supportingUrls")), "Final result needs supporting notice")
            if state == "SUBMISSION_WINDOW_ENDED":
                times = [dt.datetime.fromisoformat(row[k]) for k in ("acquisitionStart", "acquisitionEnd", "submissionEnd")]
                require(all(t.utcoffset() == dt.timedelta(hours=8) for t in times), "Unzoned or non-Beijing deadline")
                require(times[0] <= times[1] <= times[2] and times[2].date() < checked, "Incorrect ended-window status")
        else:
            require(state in ACTIVITY_STATES and not row.get("projectNumber"), "Invalid activity state")
            require(row.get("evidenceClass") in ACTIVITY_CLASSES, "Activity evidence type missing")
            require(not local_roles & (WINNER_ROLES | {"BUYER", "SHORTLISTED_CANDIDATE", "DISCLOSING_PARENT",
                                                      "BID_PARTICIPANT", "CONSORTIUM_BID_PARTICIPANT"}),
                    "Procurement role in activity")
            if state in {"HELD", "COOPERATION_SIGNED"}:
                require(row.get("occurredOn") or row.get("occurredPeriod"), "Occurrence evidence missing")
                if row.get("occurredOn"):
                    require(dt.date.fromisoformat(row["occurredOn"]) <= checked, "Future event is not held")
                if period := row.get("occurredPeriod"):
                    if isinstance(period, dict):
                        require(dt.date.fromisoformat(period["from"]) <= dt.date.fromisoformat(period["through"]) <= checked,
                                "Invalid held event period")
                    else:
                        # Preserve the reviewed imprecise source date; do not invent a day.
                        require(row["id"] == "EV-20260908-026" and period == "2026年6月下旬"
                                and dt.date(2026, 6, 30) <= checked, "Unreviewed imprecise event date")
            if state == "COOPERATION_SIGNED":
                require("SIGNATORY" in local_roles, "Signed cooperation needs signatory evidence")
            if row.get("startsOn") and row.get("endsOn"):
                require(dt.date.fromisoformat(row["startsOn"]) <= dt.date.fromisoformat(row["endsOn"]), "Invalid event dates")
        existing = row.get("existingSourceIds", [])
        require(row.get("proposedAction") in {"ENRICH_EXISTING_SOURCE_WITH_EVIDENCE", "ADD_AFTER_HISTORICAL_NOTICE_UI",
                "ADD_AFTER_ACTIVITY_SCHEMA_AND_UI", "ADD_AFTER_ACTIVITY_SOURCE_UI"}, "Unreviewed import action")
        if row["category"] == "ACTIVITY":
            require(row["proposedAction"] in {"ADD_AFTER_ACTIVITY_SCHEMA_AND_UI", "ADD_AFTER_ACTIVITY_SOURCE_UI"},
                    "Activity must not be imported as tender")
        if existing:
            require(row["proposedAction"] == "ENRICH_EXISTING_SOURCE_WITH_EVIDENCE", "Do not duplicate existing project")
            old = [t for t in baseline["externalOpportunities"] if t["sourceId"] in existing]
            require(len(old) == len(existing) and all(t["source"]["项目编号"] == row["projectNumber"] for t in old),
                    "Existing project link mismatch")
        if amount := row.get("amount"):
            require(amount.get("currency") == "CNY" and re.fullmatch(r"\d+\.\d{2}", amount.get("value", "")), "Invalid amount")
            bases = {"AWARD", "CONTRACT", "BID_QUOTE", "ESTIMATED_CONTRACT", "ESTIMATED_PROCUREMENT"}
            if annual:
                bases.add("CONSORTIUM_TOTAL_AWARD")
            require(amount.get("basis") in bases, "Amount must distinguish quote, award, estimate and contract")
            if annual and "CONSORTIUM_AWARDED_MEMBER" in local_roles:
                require(amount["basis"] == "CONSORTIUM_TOTAL_AWARD", "Consortium total is not a single-member award")
            if amount["basis"] == "CONSORTIUM_TOTAL_AWARD":
                require("CONSORTIUM_AWARDED_MEMBER" in local_roles, "Consortium amount without consortium role")
            if state in {"CANDIDATE_NOTICE", "PARTICIPATION_RECORDED"}:
                require(amount["basis"] == "BID_QUOTE", "Participant quote is not awarded amount")
        if share := row.get("enterpriseShare"):
            require(annual and row.get("evidenceClass") == "ISSUER_DISCLOSURE" and share.get("basis") == "ISSUER_APPROXIMATION",
                    "Unverified member share")
            require(share.get("sourceId") in local_orgs and Decimal("0") < Decimal(share["workloadPercent"]) <= Decimal("100"),
                    "Invalid consortium share")
            require(Decimal("0") < Decimal(share["amountApproxYuan"]) <= Decimal(row["amount"]["value"]), "Share exceeds total")
        if lots := row.get("lots"):
            require(annual and state == "AWARDED" and not row.get("amount"), "Rate-only lots are not currency amounts")
            require(len({lot["lot"] for lot in lots}) == len(lots), "Duplicate lot")
            for lot in lots:
                require(lot["sourceId"] in local_orgs and Decimal("0") < Decimal(lot["awardRatePercent"]) <= Decimal("100"), "Invalid lot rate")
    if annual:
        coverage = catalog.get("coverage", {})
        covered = coverage.get("enterprises", [])
        require(len(covered) == len(enterprises) == coverage.get("enterpriseCount") == coverage.get("exactNameQueries"),
                "Incomplete enterprise coverage")
        require({e["sourceId"] for e in covered} == set(enterprises), "Coverage identities missing/duplicated")
        held_ids = {r["id"] for r in catalog["heldForReview"]}
        require(len(held_ids) == len(catalog["heldForReview"]) and held_ids.isdisjoint(seen_ids), "Held and reviewed records mixed")
        for item in covered:
            org = item["sourceId"]
            require(item["enterpriseName"] == enterprises[org]["profile"]["name"] and item.get("exhaustive") is False,
                    "Cannot claim exhaustive coverage or change identity")
            require(item.get("searchStatus") == "EXACT_NAME_INITIAL_SCREEN_COMPLETED"
                    and '"' + item["enterpriseName"] + '"' in item["exactNameQuery"], "Missing name query evidence")
            expected = {r["id"] for r in rows if any(e["sourceId"] == org for e in r["enterpriseRelations"])}
            require(set(item["reviewedRecordIds"]) == expected, "Coverage record mismatch")
            require(set(item["heldRecordIds"]) <= held_ids, "Unknown held reference")
        for item in catalog["heldForReview"]:
            require(item.get("status") == "HELD_NOT_IMPORTABLE" and item.get("reason"), "Held item cannot be imported")
            require(set(item["relatedEnterpriseSourceIds"]) <= set(enterprises), "Unknown held identity")
    return {"status": "validated-not-imported", "records": len(rows), "enterpriseIdentities": len(orgs),
            "categories": dict(Counter(r["category"] for r in rows)),
            "states": dict(Counter(r["status"] for r in rows)),
            "existingProjectEnrichments": sum(bool(r["existingSourceIds"]) for r in rows),
            "heldForReview": len(catalog.get("heldForReview", [])), "databaseWrites": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    args = parser.parse_args()
    import hashlib
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    raw_baseline = args.baseline.read_bytes()
    require(hashlib.sha256(raw_baseline).hexdigest() == catalog["enterpriseBaseline"]["sha256"], "Baseline checksum mismatch")
    print(json.dumps(validate(catalog, json.loads(raw_baseline)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
