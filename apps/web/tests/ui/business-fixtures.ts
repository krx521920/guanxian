import type { BusinessResult } from '../../src/services/assistant-business-results'
import type { MemberProfile } from '../../src/types/domain'

// All records in this file are synthetic test fixtures, never imported into any database.
export const fixtureAssociation = '10000000-0000-4000-8000-000000000001'
export const fixtureMembers: MemberProfile[] = ['虚构·京华监测科技', '虚构·北辰工程咨询', '虚构·明川设备'].map((name, index) => ({
  id: `20000000-0000-4000-8000-00000000000${index + 1}`, associationId: fixtureAssociation, name,
  category: index === 2 ? '设备制造' : '技术服务', unifiedSocialCreditCode: 'TEST-NOT-A-REAL-CREDIT-CODE',
  address: '虚构测试地址', contactName: '测试联系人', contactPhone: '000-00000000', contactEmail: 'demo@example.invalid',
  introduction: '此为自动化验收的虚构企业，未导入正式环境。', capabilities: ['管线监测', '数据治理'],
  products: ['测试监测平台'], services: index === 1 ? [] : ['数据服务'], applicationScenarios: ['虚构燃气场景'], cooperationNeeds: [],
  visibility: 'MEMBERS', status: 'ACTIVE', version: 1, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T10:00:00Z',
  deletedAt: null, deletedBySubject: null, statusBeforeDelete: null,
}))
export function businessFixture(kind = 'MEMBERS'): BusinessResult {
  return { schemaVersion: 1, id: '30000000-0000-4000-8000-000000000001', kind, status: 'OK', label: kind === 'RECOMMENDATIONS' ? '候选企业条件核对' : '会员企业查询',
    associationId: fixtureAssociation, scope: '本地模拟的授权范围；以下均为虚构资料，非正式业务查询。',
    filters: kind === 'RECOMMENDATIONS' ? { 依据: '指定候选企业档案，条件详见逐项核对' } : { 关键词: '监测' }, queriedAt: '2026-01-03T10:00:00Z', total: kind === 'MEMBERS' ? 12 : 3,
    items: fixtureMembers.map(m => ({ id: m.id, name: m.name, target: 'MEMBER',
      fields: { category: m.category, capabilities: m.capabilities.join('、'), products: m.products.join('、'), services: (m.services || []).join('、'), status: m.status, updatedAt: m.updatedAt },
      evidence: kind === 'RECOMMENDATIONS' ? [
        { criterion: '管线监测', field: 'capabilities', state: 'MATCHED', observed: '管线监测、数据治理', explanation: '登记文字包含此条件，仍需人工核实实际履约能力。' },
        { criterion: '应急抢修', field: 'services', state: 'INSUFFICIENT', observed: '', explanation: '资料不足不等于没有能力。' },
        { criterion: '施工企业', field: 'category', state: 'UNMET', observed: m.category, explanation: '登记分类与指定值不同。' },
      ] : [],
    })),
  }
}
