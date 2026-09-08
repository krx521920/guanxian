import type { BusinessResult } from '../../src/services/assistant-business-results'
import { fixtureAssociation } from './business-fixtures'

// Synthetic browser/unit fixture only; never imported into a database.
export function sourceEvidenceFixture(kind: 'TENDER' | 'ACTIVITY' = 'TENDER'): BusinessResult {
  return { schemaVersion: 1, id: '40000000-0000-4000-8000-000000000001', kind: `${kind}_EVIDENCE`, status: 'OK',
    label: kind === 'TENDER' ? '外部招采与历史结果查询' : '企业活动与公开动态查询', associationId: fixtureAssociation,
    scope: '本地模拟授权范围；非生产资料', filters: { 关键词: '监测', 发布日期: '2025-09-08 至 2026-09-08；排除缺失日期' },
    queriedAt: '2026-09-08T10:00:00Z', total: 3, items: [{ id: '50000000-0000-4000-8000-000000000001',
      name: kind === 'TENDER' ? '虚构·管线监测候选公示' : '虚构·企业活动报道', target: 'SOURCE', evidence: [],
      fields: { 发布日期: '2026-08-01', 来源记录状态: kind === 'TENDER' ? '候选公示' : '活动报道',
        关联企业及角色: '虚构企业：候选单位，不等于中标', 金额及口径: '项目总额100万元；企业份额未披露',
        核验边界: '已入库快照，并非实时检索；不代表仍可投标或平台确认合作。' },
      source: { kind, sourceId: 'SRC-FIXTURE', evidenceRecordId: 'EV-FIXTURE', checkedOn: '2026-09-08',
        sourceUrl: 'https://example.test/notice', supportingUrls: ['https://example.test/correction'] },
    }] }
}
