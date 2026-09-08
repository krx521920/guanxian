import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("annual_evidence", ROOT / "tools/operations/validate_member_public_evidence.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AnnualEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "data/curated/member-annual-evidence-20260908.json").read_text(encoding="utf-8"))
        # Only an in-memory validation fixture; never imports or writes enterprise data.
        orgs = {e["sourceId"]: {"sourceId": e["sourceId"], "profile": {"name": e["enterpriseName"]},
                "membershipEvidence": "SOURCE_LISTED_NOT_INDEPENDENTLY_VERIFIED"}
                for e in self.catalog["coverage"]["enterprises"]}
        for row in self.catalog["records"]:
            for e in row["enterpriseRelations"]:
                orgs[e["sourceId"]]["membershipEvidence"] = e["membershipEvidence"]
        self.baseline = {"enterprises": list(orgs.values()), "externalOpportunities": [
            {"sourceId": "BID-049", "source": {"项目编号": "S110000A001044613001"}}]}

    def test_annual_reviewed_catalog(self):
        result = module.validate(self.catalog, self.baseline)
        self.assertEqual(result["records"], 64)
        self.assertEqual(result["categories"], {"TENDER": 41, "ACTIVITY": 23})
        self.assertEqual(result["enterpriseIdentities"], 32)
        self.assertEqual(result["heldForReview"], 17)
        self.assertEqual(result["databaseWrites"], 0)

    def test_previous_quarter_preserved(self):
        previous = json.loads((ROOT / "data/curated/member-public-evidence-20260908.json").read_text(encoding="utf-8"))
        reused = [r for r in self.catalog["records"] if r["reviewBatch"] == "PRIOR_QUARTER_REVIEW"]
        for row in reused:
            row.pop("reviewBatch")
        self.assertEqual(reused, previous["records"])
        self.assertEqual(len(reused), 31)

    def test_rejects_incorrect_annual_adaptations(self):
        def row(c, id):
            return next(r for r in c["records"] if r["id"] == id)
        mutations = {
            "single member total": lambda c: row(c, "AN-015")["amount"].update(basis="AWARD"),
            "disclosure contract": lambda c: row(c, "AN-015").update(signedFinalContractVerified=True),
            "share exceeds total": lambda c: row(c, "AN-015")["enterpriseShare"].update(amountApproxYuan="999999999"),
            "rate becomes yuan": lambda c: row(c, "AN-029").update(amount={"value": "99.00", "basis": "AWARD", "currency": "CNY"}),
            "candidate became winner": lambda c: row(c, "AN-031")["enterpriseRelations"][0].update(role="AWARDED_SUPPLIER"),
            "quote became award": lambda c: row(c, "AN-024")["amount"].update(basis="AWARD"),
            "secondary tender": lambda c: row(c, "AN-001").update(evidenceClass="MEDIA_REPRINT"),
            "unsafe source": lambda c: row(c, "AN-001").update(sourceUrl="https://www.ccgp.gov.cn.evil.test/a.htm"),
            "lost final evidence": lambda c: row(c, "AN-030").update(supportingUrls=[]),
            "future held event": lambda c: row(c, "AN-026")["occurredPeriod"].update(through="2027-01-01"),
            "wrong year": lambda c: c["window"].update(publicationFrom="2024-09-08"),
            "missing enterprise coverage": lambda c: c["coverage"]["enterprises"].pop(),
            "false exhaustive": lambda c: c["coverage"]["enterprises"][0].update(exhaustive=True),
            "invented match": lambda c: c["coverage"]["enterprises"][0]["reviewedRecordIds"].append("AN-999"),
            "held promoted": lambda c: c["heldForReview"][0].update(status="APPROVED_FOR_IMPORT"),
            "activity as tender": lambda c: row(c, "AN-026").update(proposedAction="ADD_AFTER_HISTORICAL_NOTICE_UI"),
            "member consent fabricated": lambda c: row(c, "AN-001").update(publicPublicationGranted=True),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(self.catalog)
                mutate(changed)
                with self.assertRaises(ValueError):
                    module.validate(changed, self.baseline)


if __name__ == "__main__":
    unittest.main()
