import { request } from './http'
import type { MemberProfile, MemberUpsertPayload } from '../types/domain'

export interface PublicEnterprise {
  id: string; name: string; category: string; introduction: string | null
  capabilities: string[]; products: string[]; services: string[]; applicationScenarios: string[]
  publicationId: string; publishedAt: string
}
export interface ProfileDraft {
  id: string; baseVersion: number; content: MemberUpsertPayload
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'; editors: string[]
  submittedBy: string | null; reviewNote: string | null; reviewedBy: string | null
  submittedAt: string | null; reviewedAt: string | null
}
export interface ProfileWorkflow {
  official: MemberProfile; draft: ProfileDraft | null
  approved: { id: string; profile: MemberProfile; consentedAt: string | null; approvedAt: string } | null
  publication: PublicEnterprise | null; version: number; published: boolean
  canEdit: boolean; canReview: boolean; canConsent: boolean; canPublish: boolean; canWithdraw: boolean
}
export const profileFields = [
  ['category', '业务类别'], ['address', '联系地址（内部）'], ['contactName', '联系人（内部）'],
  ['contactPhone', '联系电话（内部）'], ['contactEmail', '联系邮箱（内部）'], ['introduction', '企业简介'],
  ['capabilities', '技术能力'], ['products', '产品'], ['services', '服务'],
  ['applicationScenarios', '应用场景'], ['cooperationNeeds', '合作需求（内部）'],
] as const
export const publicFields = ['name', 'category', 'introduction', 'capabilities', 'products', 'services', 'applicationScenarios'] as const
export function profileDiff(official: MemberProfile, draft: MemberUpsertPayload) {
  return profileFields.filter(([key]) => JSON.stringify(official[key] ?? '') !== JSON.stringify(draft[key] ?? ''))
    .map(([key, label]) => ({ key, label, before: displayField(official[key]), after: displayField(draft[key]) }))
}
export function displayField(value: unknown): string { return Array.isArray(value) ? value.join('、') || '—' : String(value || '—') }
export function publicPreview(profile: MemberProfile) {
  return Object.fromEntries(publicFields.map(key => [key, profile[key]]))
}
export const profileApi = {
  get: (id: string) => request<ProfileWorkflow>(`/enterprise-profiles/${encodeURIComponent(id)}`, { cache: 'no-store' }),
  save: (id: string, version: number, baseVersion: number, content: MemberUpsertPayload) =>
    mutate(id, version, 'draft', { content, baseVersion }, 'PUT'),
  action: (id: string, version: number, action: 'submit' | 'review' | 'consent' | 'publish' | 'withdraw', body?: unknown) =>
    mutate(id, version, action, body),
}
function mutate(id: string, version: number, action: string, body?: unknown, method = 'POST') {
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('流程版本无效，请重新加载')
  return request<ProfileWorkflow>(`/enterprise-profiles/${encodeURIComponent(id)}/${action}`, {
    method, cache: 'no-store', headers: { 'If-Match': `"${version}"` }, body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// Public transport must never initialize OIDC or attach internal tokens/context headers.
export async function publicEnterprises(query = '', page = 0): Promise<PublicEnterprise[]> {
  const base = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
  const response = await fetch(`${base}/public/enterprises?${new URLSearchParams({ q: query, page: String(page) })}`, {
    credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(15000),
  })
  if (!response.ok) throw new Error('公开目录暂时无法读取，请稍后重试')
  const body = await response.json()
  if (body.code !== 'OK' || !Array.isArray(body.data)) throw new Error('公开目录响应异常')
  return body.data
}
