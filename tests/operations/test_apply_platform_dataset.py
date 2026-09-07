import hashlib
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools/operations"))
from apply_platform_dataset import json_sql, mapped_bundle, render_sql, reviewed_targets
from prepare_platform_dataset import prepare
import test_prepare_platform_dataset as fixtures


class ApplyDatasetSafetyTests(unittest.TestCase):
    def test_audited_targets_are_explicit_unique_and_include_later_demo_matches(self):
        targets = reviewed_targets()
        ids = [r['id'] for rows in targets.values() for r in rows]
        self.assertEqual(len(ids),35)
        self.assertEqual(len(set(ids)),35)
        self.assertEqual(len(targets['ecosystem_match']),8)

    def test_source_text_is_not_interpolated_as_sql(self):
        payload = "'; DROP TABLE enterprise; --"
        self.assertNotIn(payload,json_sql({'text':payload}))

    def test_default_is_database_read_only_and_mapping_is_deterministic(self):
        blob, _ = fixtures.PrepareDatasetTests().fixture()
        bundle = prepare(blob,hashlib.sha256(blob).hexdigest())
        self.assertEqual(mapped_bundle(bundle),mapped_bundle(bundle))
        readonly = render_sql(bundle)
        self.assertTrue(readonly.startswith('\nBEGIN READ ONLY;'))
        for operation in ('UPDATE enterprise','DELETE FROM','INSERT INTO','TRUNCATE'):
            self.assertNotIn(operation,readonly)
        write = render_sql(bundle,execute=True)
        self.assertIn('IN SHARE ROW EXCLUSIVE MODE',write)
        self.assertIn('Existing real enterprise name collision',write)
        self.assertNotIn('TRUNCATE',write)
        self.assertNotIn('DELETE FROM enterprise ',write)
        self.assertNotIn('ON CONFLICT',write)


if __name__ == '__main__':
    unittest.main()
