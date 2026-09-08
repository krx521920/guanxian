import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/operations'))
import import_member_annual_evidence as importer


def fixture():
    raw = (ROOT / 'data/curated/member-annual-evidence-20260908.json').read_bytes()
    catalog = json.loads(raw)
    relations = {r['sourceId']: r for row in catalog['records'] for r in row['enterpriseRelations']}
    baseline = {'enterprises': [
        {'sourceId': r['sourceId'], 'profile': {'name': r['enterpriseName']},
         'membershipEvidence': relations.get(r['sourceId'], {}).get('membershipEvidence', 'SOURCE_LISTED_NOT_INDEPENDENTLY_VERIFIED')}
        for r in catalog['coverage']['enterprises']],
        'externalOpportunities': [{'sourceId': 'BID-049', 'source': {'项目编号': 'S110000A001044613001'}}]}
    encoded = json.dumps(baseline, ensure_ascii=False).encode()
    # Fixture baseline hash is changed in this test process only. Production CLI pins the real reviewed baseline.
    with patch.object(importer, 'BASELINE_SHA', hashlib.sha256(encoded).hexdigest()):
        plan = importer.prepare(raw, encoded)
    return raw, encoded, plan


class AnnualImportTest(unittest.TestCase):
    def test_hash_guards_and_exact_counts(self):
        raw, baseline, plan = fixture()
        with self.assertRaisesRegex(ValueError, 'catalog checksum'):
            importer.prepare(raw + b' ', baseline)
        with self.assertRaisesRegex(ValueError, 'baseline checksum'):
            importer.prepare(raw, baseline)
        self.assertEqual(plan['counts']['insert'], 63)
        self.assertEqual(plan['counts']['roleLinks'], 81)
        self.assertEqual(plan['counts']['enterpriseIdentities'], 32)
        self.assertEqual([r['existingSourceId'] for r in plan['records'] if r['existingSourceId']], ['BID-049'])
        self.assertFalse(any(r['sourceId'].startswith('H') for r in plan['records']))

    def test_preserves_roles_dates_amount_basis_and_raw_evidence(self):
        raw, _, plan = fixture()
        records = {r['id']: r for r in json.loads(raw)['records']}
        for item in plan['records']:
            self.assertEqual(item['payload']['publicEvidence']['record'], records[item['sourceId']])
        fields = {r['sourceId']: r['payload']['source'] for r in plan['records']}
        self.assertIn('联合体中标总额', fields['AN-015']['金额及口径'])
        self.assertIn('发行人估计', fields['AN-015']['披露份额'])
        self.assertNotIn('金额及口径', fields['AN-029'])
        self.assertIn('99.00%', fields['AN-029']['分标段信息'])
        self.assertEqual(fields['EV-20260908-026']['活动时间'], '2026年6月下旬')
        self.assertIn('非中标金额', fields['AN-024']['金额及口径'])
        self.assertIn('其他供应商', fields['AN-030']['后续结果'])
        self.assertTrue(all('核验边界' in r['payload']['source'] for r in plan['records']))

    def test_default_sql_has_no_mutations_and_rollback_is_scoped(self):
        _, _, plan = fixture()
        sql = importer.render_sql(plan)
        self.assertTrue(sql.startswith('BEGIN READ ONLY;'))
        for operation in ['INSERT INTO ', 'UPDATE platform_', 'DELETE FROM ', 'ALTER TABLE ', 'CREATE TABLE ']:
            self.assertNotIn(operation, sql)
        write = importer.render_sql(plan, execute=True)
        self.assertIn('LOCK TABLE association,enterprise', write)
        self.assertNotIn('UPDATE enterprise', write)
        self.assertNotIn('INSERT INTO cooperation', write)
        self.assertNotIn('INSERT INTO user_', write)
        rollback = importer.render_sql(plan, execute=True, rollback=True)
        self.assertIn('enriched row modified', rollback)
        self.assertIn('AND import_id=', rollback)
        self.assertNotIn('DELETE FROM enterprise', rollback)
        self.assertNotIn('DELETE FROM audit_log', rollback)


if __name__ == '__main__': unittest.main()
