import { describe, expect, it, vi } from 'vitest'
import { request } from './http'
import { policyCandidates, policyCandidateOverview, assessmentKind, candidateTimeWarning, type PolicyCandidate } from './policy-candidates'
vi.mock('./http', () => ({ request: vi.fn().mockResolvedValue({ items: [], total: 0 }) }))

describe('policy candidate query', () => {
  it('does not present historical or indirect clues as current eligibility', () => {
    const candidate: PolicyCandidate = { enterpriseId: 'company', enterpriseName: '测试', category: null, enterpriseVersion: 0,
      relevance: 'LIMITED_CLUES', matchedTopics: 1, evidence: [], missingEvidence: [],
      assessment: { kind: 'INDIRECT_BUSINESS_CLUE', applicability: 'UNVERIFIED', checks: [{ dimension: '效力', state: 'OBSOLETE_AT_SOURCE', explanation: '已废止' }] } }
    expect(candidateTimeWarning(candidate)).toContain('历史参考')
    expect(assessmentKind(candidate.assessment!.kind)).toContain('间接业务线索')
    candidate.assessment!.checks = [{ dimension: '效力', state: 'NOT_YET_EFFECTIVE', explanation: '未来施行' }]
    expect(candidateTimeWarning(candidate)).toContain('提前了解')
    candidate.assessment!.checks = [{ dimension: '效力', state: 'RECHECK_REQUIRED', explanation: '待核验' }]
    expect(candidateTimeWarning(candidate)).toBeNull()
  })
  it('batches policy discovery without accepting a caller tenant', async () => {
    await policyCandidateOverview('供水 & 监测', 2)
    expect(request).toHaveBeenLastCalledWith('/policy-enterprise-candidates?q=%E4%BE%9B%E6%B0%B4%20%26%20%E7%9B%91%E6%B5%8B&page=2&size=10')
  })
  it('uses the authenticated read-only transport and encodes names literally', async () => {
    await policyCandidates('policy-id', '设备 & 监测', 2, 10)
    expect(request).toHaveBeenLastCalledWith('/policies/policy-id/enterprise-candidates?q=%E8%AE%BE%E5%A4%87%20%26%20%E7%9B%91%E6%B5%8B&page=2&size=10')
  })
  it('does not accept caller-supplied tenant or enterprise scope', async () => {
    await policyCandidates('policy-id')
    expect(request).toHaveBeenLastCalledWith('/policies/policy-id/enterprise-candidates?q=&page=0&size=20')
  })
})
