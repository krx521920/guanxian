import hashlib
import csv
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

PATH = Path(__file__).resolve().parents[2] / "tools/operations/prepare_platform_dataset.py"
spec = importlib.util.spec_from_file_location("prepare_dataset", PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PrepareDatasetTests(unittest.TestCase):
    def fixture(self, bad_reference=False):
        tables = {
            "01_企业及产品服务.csv": [{"企业ID": f"ORG-{i:03d}", "企业名称": f"测试单位{i}",
                "主体类型": "企业", "企业简介": "原始简介", "业务领域": "测量",
                "主要产品与服务": "测量设备；检测服务", "能力与资质": "",
                "已知协会关系": "有入会申请资料", "联系电话": "", "网址": "", "地址": ""}
                for i in range(1, 117)],
            "02_政策与标准.csv": [{"政策标准ID": f"POL-{i:03d}", "名称": f"测试政策{i}",
                "发布机构": "机构", "文号或标准号": "", "发布日期": "2026-09-05",
                "实施日期": "2026-09-01", "官方原文链接": f"https://example.com/p/{i}",
                "文件类型": "标准", "适用地区": "北京", "核心要求": "概要",
                "企业需关注": "", "版本说明": "", "全文状态": "未归档"} for i in range(1, 41)],
            "03_关联协会.csv": [{"协会ID": f"ASSOC-{i:03d}", "协会名称": f"测试协会{i}", "官网": ""}
                for i in range(1, 6)],
            "04_近期招投标需求.csv": [{"招标ID": f"BID-{i:03d}", "项目名称": f"测试项目{i}",
                "建议匹配企业（ID）": "无高置信直接匹配" if i == 1 else "ORG-001 测试单位1",
                "截止或开标时间": "2026-09-28 09:30:00", "原文链接": f"https://example.com/b/{i}",
                "核验日期": "2026-09-05", "当前状态": "可获取文件"} for i in range(1, 51)],
        }
        if bad_reference:
            tables["04_近期招投标需求.csv"][0]["建议匹配企业（ID）"] = "ORG-999 不存在的单位"
        b = io.BytesIO()
        with zipfile.ZipFile(b, "w") as z:
            for filename, rows in tables.items():
                text = io.StringIO()
                w = csv.DictWriter(text, fieldnames=list(rows[0]))
                w.writeheader()
                w.writerows(rows)
                z.writestr(filename, text.getvalue().encode("utf-8-sig"))
        return b.getvalue(), tables

    def test_valid_bundle_is_lossless_and_does_not_invent_missing_data(self):
        blob, original = self.fixture()
        bundle = module.prepare(blob, hashlib.sha256(blob).hexdigest())
        self.assertEqual(sum(bundle["counts"].values()), 211)
        self.assertFalse(bundle["productionApplied"])
        for filename, (kind, _, _, _) in module.TABLES.items():
            self.assertEqual([r["source"] for r in bundle[kind]], original[filename])
        member = bundle["enterprises"][0]
        self.assertIsNone(member["profile"]["contactPhone"])
        self.assertIsNone(member["profile"]["contactName"])
        self.assertIsNone(member["profile"]["unifiedSocialCreditCode"])
        self.assertEqual(member["profile"]["products"], [])
        self.assertIn("测量设备；检测服务", member["profile"]["intro"])
        self.assertEqual(member["membershipEvidence"], "APPLICATION_ONLY")
        self.assertFalse(bundle["policies"][0]["fullTextArchived"])
        self.assertFalse(bundle["associationDirectory"][0]["sharingPermissionGranted"])
        self.assertEqual(bundle["externalOpportunities"][0]["suggestedEnterpriseSourceIds"], [])
        self.assertEqual(bundle["externalOpportunities"][1]["suggestedEnterpriseSourceIds"], ["ORG-001"])
        self.assertEqual(bundle["externalOpportunities"][0]["deadlineAt"], "2026-09-28T09:30:00+08:00")

    def test_unresolved_business_reference_is_not_silently_discarded(self):
        blob, _ = self.fixture(bad_reference=True)
        with self.assertRaisesRegex(ValueError, "reference"):
            module.prepare(blob, hashlib.sha256(blob).hexdigest())

    def test_preserves_name_identity_for_collision_review_only(self):
        self.assertEqual(module.normalized_name(" A（分公司） "), "A(分公司)")

    def test_missing_or_bare_url_is_not_invented(self):
        self.assertIsNone(module.safe_link(""))
        self.assertIsNone(module.safe_link("www.example.com", allow_bare=True))
        self.assertEqual(module.safe_link("https://example.com/a"), "https://example.com/a")

    def test_unsafe_source_links_rejected(self):
        for url in ("javascript:alert(1)", "https://u:p@example.com", "file:///etc/passwd", "https://example.com/a b"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                module.safe_link(url)

    def test_checksum_required_before_reading(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            module.prepare(b"wrong archive", "0" * 64)

    def test_path_traversal_and_unknown_files_rejected(self):
        for name in ("../01_企业及产品服务.csv", "x/run.py", "C:/data.csv"):
            b = io.BytesIO()
            with zipfile.ZipFile(b, "w") as z:
                z.writestr(name, "x")
            with self.subTest(name=name), self.assertRaises(ValueError):
                module.prepare(b.getvalue(), hashlib.sha256(b.getvalue()).hexdigest())

    def test_missing_tables_rejected(self):
        b = io.BytesIO()
        with zipfile.ZipFile(b, "w") as z:
            z.writestr("README.md", "untrusted prose")
        with self.assertRaisesRegex(ValueError, "missing"):
            module.prepare(b.getvalue(), hashlib.sha256(b.getvalue()).hexdigest())


if __name__ == "__main__":
    unittest.main()
