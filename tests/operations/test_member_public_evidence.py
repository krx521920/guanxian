import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("member_evidence", ROOT / "tools/operations/validate_member_public_evidence.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EvidenceSafetyTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "data/curated/member-public-evidence-20260908.json").read_text(encoding="utf-8"))
        # Synthetic identity fixture only, never an imported member/demo dataset.
        orgs = {f"ORG-{i:03}": {"sourceId": f"ORG-{i:03}", "profile": {"name": f"fixture-{i}"},
                "membershipEvidence": "SOURCE_LISTED_NOT_INDEPENDENTLY_VERIFIED"} for i in range(1, 117)}
        for row in self.catalog["records"]:
            for r in row["enterpriseRelations"]:
                orgs[r["sourceId"]] = {"sourceId": r["sourceId"], "profile": {"name": r["enterpriseName"]},
                                      "membershipEvidence": r["membershipEvidence"]}
        self.baseline = {"enterprises": list(orgs.values()), "externalOpportunities": [
            {"sourceId": "BID-049", "source": {"项目编号": "S110000A001044613001"}}]}

    def test_reviewed_catalog(self):
        result = module.validate(self.catalog, self.baseline)
        self.assertEqual(result["records"], 31)
        self.assertEqual(result["categories"], {"TENDER": 21, "ACTIVITY": 10})
        self.assertEqual(result["enterpriseIdentities"], 17)
        self.assertEqual(result["existingProjectEnrichments"], 1)
        self.assertEqual(result["databaseWrites"], 0)

    def test_rejects_unsafe_or_fabricated_changes(self):
        def wrong_existing(c):
            next(r for r in c["records"] if r["existingSourceIds"])["proposedAction"] = "INSERT_DUPLICATE"
        def future_event(c):
            next(r for r in c["records"] if r["status"] == "HELD")["occurredOn"] = "2027-01-01"
        def candidate_winner(c):
            next(r for r in c["records"] if r["status"] == "CANDIDATE_NOTICE")["enterpriseRelations"][0]["role"] = "AWARDED_SUPPLIER"
        def old_membership(c):
            next(x for r in c["records"] for x in r["enterpriseRelations"] if x["sourceId"] == "ORG-047")["membershipEvidence"] = "VERIFIED_MEMBER"
        def wrong_tender(c):
            next(r for r in c["records"] if r["category"] == "ACTIVITY")["category"] = "TENDER"
        mutations = {
            "fake production": lambda c: c.update(productionApplied=True),
            "duplicate source": lambda c: c["records"].append(copy.deepcopy(c["records"][0])),
            "unknown enterprise": lambda c: c["records"][0]["enterpriseRelations"][0].update(sourceId="ORG-999"),
            "wrong legal name": lambda c: c["records"][0]["enterpriseRelations"][0].update(enterpriseName="不同法人"),
            "consent fabrication": lambda c: c["records"][0].update(publicPublicationGranted=True),
            "platform cooperation fabrication": lambda c: c["records"][0].update(confirmedPlatformCollaborationCreated=True),
            "member authored fabrication": lambda c: c["records"][0].update(memberAuthored=True),
            "unverified eligibility": lambda c: c["records"][0]["enterpriseRelations"][0].update(participationEligibility="QUALIFIED"),
            "credentials in URL": lambda c: c["records"][0].update(sourceUrl="https://secret:token@www.ccgp.gov.cn/a.htm"),
            "outside period": lambda c: c["records"][0].update(publishedOn="2025-09-01"),
            "wrong amount unit": lambda c: c["records"][0]["amount"].update(basis="UNKNOWN"),
            "duplicate existing project": wrong_existing,
            "future event held": future_event,
            "candidate is not winner": candidate_winner,
            "membership must not upgrade": old_membership,
            "activity is not tender": wrong_tender,
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(self.catalog)
                mutate(changed)
                with self.assertRaises(ValueError):
                    module.validate(changed, self.baseline)


if __name__ == "__main__":
    unittest.main()
