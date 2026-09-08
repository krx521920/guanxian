import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/operations"))
import prepare_platform_corrections as corrections


def correction_fixture():
    policies = []
    for source_id, title, number, effective, version in (
        ("STD-005", "城市综合管廊工程技术标准（2024年版）", "GB/T 50838-2024", "2025-01-01", "替代GB 50838-2015。"),
        ("POL-005", "北京市地下空间专项规划（2025年—2035年）", "测试文号", "2026-07-24", "测试规划期"),
    ):
        source = {"文号或标准号": number, "实施日期": effective, "版本说明": version,
                  "核心要求": "供水监测测试摘要", "企业需关注": "测试", "全文状态": "未归档", "涉及领域": "供水监测"}
        policies.append({"sourceId": source_id, "title": title, "documentNumber": number,
                         "effectiveOn": effective, "publishedOn": "2024-11-29", "sourceUrl": "https://example.test/" + source_id,
                         "category": "测试政策", "region": "全国", "issuingAuthority": "测试机构", "source": source,
                         "summary": "\n".join(f"{k}：{source[k]}" for k in ("核心要求", "企业需关注", "版本说明", "全文状态"))})
    return {"sourceSha256": corrections.SOURCE_SHA, "enterprises": [], "associationDirectory": [], "policies": policies,
            "externalOpportunities": [{"sourceId": "BID-041", "title": "永定路西里老旧小区市政排水管线改造项目",
                "sourceUrl": corrections.COMBINED_NOTICE, "source": {"原文链接": corrections.COMBINED_NOTICE}}]}


class CorrectionPreparationTests(unittest.TestCase):
    def test_preserves_input_and_original_source_and_keeps_uncertainty(self):
        bundle = correction_fixture()
        before = copy.deepcopy(bundle)
        plan = corrections.build_plan(bundle)
        self.assertEqual(bundle, before)
        self.assertFalse(plan["productionApplied"])
        self.assertTrue(plan["requiresBackupAndApproval"])
        by_id = {r["sourceId"]: r for r in plan["entries"]}
        for row in plan["entries"]:
            self.assertEqual(row["before"]["source"], row["after"]["source"])
            self.assertEqual(row["after"]["correction"]["applicability"], "UNVERIFIED")
        self.assertEqual(by_id["STD-005"]["after"]["documentNumber"], "GB/T 50838-2015")
        self.assertEqual(by_id["STD-005"]["after"]["effectiveOn"], "2025-04-01")
        self.assertNotIn("替代GB 50838-2015", by_id["STD-005"]["after"]["summary"])
        self.assertIsNone(by_id["POL-005"]["after"]["effectiveOn"])
        tender = by_id["BID-041"]["after"]
        self.assertEqual(tender["sourceUrl"], corrections.ORIGINAL_NOTICE)
        self.assertEqual(len(tender["sourceLinks"]), 2)
        self.assertEqual(tender["correction"]["evidenceUrls"], [])
        self.assertIn("未取到公告", tender["correction"]["reason"])

    def test_source_and_expected_values_are_exact_not_name_only_updates(self):
        for mutation in (lambda b: b.update(sourceSha256="0" * 64),
                         lambda b: b["policies"][0]["source"].update(实施日期="2025-02-01"),
                         lambda b: b["policies"][0].update(title="另一个标题")):
            bundle = correction_fixture()
            mutation(bundle)
            with self.assertRaises(ValueError):
                corrections.build_plan(bundle)

    def test_readonly_default_and_write_scope(self):
        plan = corrections.build_plan(correction_fixture())
        sql = corrections.render_sql(plan)
        self.assertIn("BEGIN READ ONLY;", sql)
        for operation in ("INSERT INTO", "UPDATE ", "DELETE ", "TRUNCATE"):
            self.assertNotIn(operation, sql)
        write = corrections.render_sql(plan, execute=True)
        self.assertIn("IN SHARE ROW EXCLUSIVE MODE", write)
        self.assertIn("Source snapshot changed", write)
        self.assertIn("Live policy changed", write)
        self.assertIn("Partial correction batch", write)
        self.assertNotIn("DELETE ", write)
        for table in ("enterprise", "user_account", "policy_impact_analysis"):
            self.assertNotIn("UPDATE " + table, write)
        self.assertNotIn("城市综合管廊", sql)  # source data is encoded, not interpolated as SQL


if __name__ == "__main__":
    unittest.main()
