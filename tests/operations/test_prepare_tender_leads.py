import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/operations'))
from prepare_tender_leads import prepare, render_sql


def catalog_fixture():
    # Public curated metadata only; tests never contact the websites or production.
    return json.loads((ROOT / 'data/curated/tender-leads-20260908.json').read_text(encoding='utf-8'))


def blob(data):
    return json.dumps(data, ensure_ascii=False).encode()


class TenderPreparationTests(unittest.TestCase):
    def test_six_leads_have_scoped_sources_no_member_writes_and_separate_deadlines(self):
        data = catalog_fixture()
        before = copy.deepcopy(data)
        plan = prepare(blob(data), {})
        self.assertEqual(data, before)
        self.assertEqual(len(plan['records']), 6)
        self.assertFalse(plan['productionApplied'])
        self.assertEqual(len({r['id'] for r in plan['records']}), 6)
        for row in plan['records']:
            self.assertEqual(row['participationEligibility'], 'UNVERIFIED')
            self.assertFalse(row['memberDemandCreated'])
            self.assertIn('未穷尽', row['source']['核验边界'])
        self.assertNotIn('截止或开标时间', plan['records'][0]['source'])
        self.assertEqual(plan['records'][1]['source']['提交截止类型'], '资格预审申请截止')
        self.assertIn('18.36万元', plan['records'][2]['source']['预算或最高限价'])

    def test_bad_source_dates_and_types_are_rejected(self):
        mutations = [lambda d: d['records'][0].update(url='https://www.ccgp.gov.cn.evil.test/a'),
                     lambda d: d['records'][0].update(url='https://www.ccgp.gov.cn/'),
                     lambda d: d['records'][1]['source'].update(公告类型='中标结果'),
                     lambda d: d['records'][1]['source'].update(提交截止类型='投标文件截止'),
                     lambda d: d['records'][0]['source'].update(文件获取截止时间='2026-09-14 00:00:00'),
                     lambda d: d['records'][1]['source'].update(截止或开标时间='2026-02-30 12:00:00'),
                     lambda d: d['records'][1]['source'].update(截止或开标时间='2026-09-01 00:00:00'),
                     lambda d: d['records'][1]['source'].update(内部电话='not-allowed'),
                     lambda d: d['records'][0]['source'].update(发布日期='2026-09-09')]
        for mutate in mutations:
            data = catalog_fixture(); mutate(data)
            with self.assertRaises(ValueError):
                prepare(blob(data), {})

    def test_deduplicates_urls_project_numbers_and_rejects_colliding_batch(self):
        data = catalog_fixture()
        first = data['records'][0]
        for old in ({'sourceUrl': first['url'].replace('https:', 'http:')},
                    {'sourceUrl': first['url'] + '；更正：https://example.test/correction'},
                    {'source': {'项目编号': first['source']['项目编号']}}, {'title': first['title']}):
            with self.assertRaises(ValueError):
                prepare(blob(data), {'externalOpportunities': [old]})
        data['records'].append(copy.deepcopy(first))
        with self.assertRaises(ValueError): prepare(blob(data), {})

    def test_duplicate_titles_with_different_urls_and_project_numbers_are_rejected(self):
        data = catalog_fixture()
        data['records'][1]['title'] = data['records'][0]['title'] + ' '
        with self.assertRaises(ValueError): prepare(blob(data), {})

    def test_sql_defaults_to_readonly_and_writes_only_source_batch_and_audit(self):
        plan = prepare(blob(catalog_fixture()), {})
        read, write = render_sql(plan), render_sql(plan, execute=True)
        self.assertIn('BEGIN READ ONLY;', read)
        self.assertNotIn('INSERT INTO', read)
        for operation in ('UPDATE ', 'DELETE ', 'TRUNCATE ', 'INSERT INTO cooperation_demand', 'INSERT INTO ecosystem_match'):
            self.assertNotIn(operation, write)
        self.assertIn('SHARE ROW EXCLUSIVE', write)
        self.assertIn('Existing notice/project collision', write)
        self.assertIn('Existing batch was modified', write)


if __name__ == '__main__':
    unittest.main()
