import { request } from './http'
import type { PolicyAssessment } from '../types/domain'

export interface PolicyCandidate {
  enterpriseId: string
  enterpriseName: string
  category: string | null
  enterpriseVersion: number
  relevance: 'MULTIPLE_CLUES' | 'LIMITED_CLUES'
  matchedTopics: number
  evidence: { topic: string; policyField: string; policyTerm: string; enterpriseField: string; enterpriseTerm: string }[]
  missingEvidence: string[]
  assessment?: PolicyAssessment
}
export interface PolicyCandidateOverview {
  items: { policyId: string; policyTitle: string; associationId: string; candidateCount: number; examples: PolicyCandidate[] }[]
  visiblePolicyCount: number; page: number; size: number; examinedEnterpriseCount: number; truncated: boolean; generatedAt: string
}
export function policyCandidateOverview(q = '', page = 0) {
  return request<PolicyCandidateOverview>(`/policy-enterprise-candidates?q=${encodeURIComponent(q)}&page=${page}&size=10`)
}
export function assessmentKind(kind: string) {
  return ({ COMPLIANCE_CLUE: '合规义务线索', BUSINESS_OPPORTUNITY: '业务机会线索', MIXED_CLUES: '义务与机会需分开核验',
    INDIRECT_OPPORTUNITY: '政府职责 · 企业间接机会', INDIRECT_BUSINESS_CLUE: '主体角色不同 · 间接业务线索', RELATED_TOPIC: '业务主题相关' } as Record<string, string>)[kind] || '待核验'
}
export function candidateTimeWarning(candidate: PolicyCandidate): string | null {
  const checks = candidate.assessment?.checks || []
  if (checks.some(check => check.state === 'OBSOLETE_AT_SOURCE')) return '来源标记已失效，仅作历史参考'
  if (checks.some(check => check.state === 'NOT_YET_EFFECTIVE')) return '尚未到所载施行日期，仅作提前了解'
  return null
}
export interface PolicyCandidatePage {
  policyId: string; policyVersion: number; policyTitle: string; method: string
  associationId: string; associationName: string; ownEnterpriseOnly: boolean; query: string
  eligibleEnterpriseCount: number; examinedCount: number; truncated: boolean
  items: PolicyCandidate[]; total: number; page: number; size: number; generatedAt: string
  policyMetadata: { domains: string | null; audience: string | null; region: string | null; sourceCheckedOn: string | null }
  limitations: string[]
}
export function policyCandidates(policyId: string, q = '', page = 0, size = 20) {
  return request<PolicyCandidatePage>(`/policies/${encodeURIComponent(policyId)}/enterprise-candidates?q=${encodeURIComponent(q)}&page=${page}&size=${size}`)
}
