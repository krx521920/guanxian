import { request } from './http'
import type { MemberProfile, MemberUpsertPayload } from '../types/domain'

export interface EnterpriseInvitation {
  id: string; enterpriseId: string; enterpriseName: string; associationName: string; username: string
  status: 'ISSUED' | 'CLAIMED' | 'APPROVED' | 'REJECTED' | 'REVOKED' | 'EXPIRED'
  version: number; createdAt: string; expiresAt: string; claimantName: string | null
  claimantSubject?: string | null; claimedAt: string | null; reviewNote: string | null; accountId: string | null
}
export const invitationStatus: Record<EnterpriseInvitation['status'], string> = {
  ISSUED: '待负责人确认', CLAIMED: '待管理员核验', APPROVED: '已开通', REJECTED: '已退回', REVOKED: '已撤销', EXPIRED: '已过期',
}
export const invitationApi = {
  list: (page = 0) => request<{ items: EnterpriseInvitation[]; total: number }>('/enterprise-invitations?page='+page+'&size=20', { cache: 'no-store' }),
  create: (enterpriseId: string, username: string) => request<{ invitation: EnterpriseInvitation; token: string }>('/enterprise-invitations', {
    method: 'POST', body: JSON.stringify({ enterpriseId, username }),
  }),
  review: (item: EnterpriseInvitation, decision: 'APPROVE' | 'REJECT', note: string) => request<EnterpriseInvitation>(`/enterprise-invitations/${item.id}/review`, {
    method: 'PUT', headers: { 'If-Match': `"${item.version}"` }, body: JSON.stringify({ decision, note }),
  }),
  revoke: (item: EnterpriseInvitation) => request<EnterpriseInvitation>(`/enterprise-invitations/${item.id}/revoke`, {
    method: 'PUT', headers: { 'If-Match': `"${item.version}"` },
  }),
  preview: (token: string) => request<EnterpriseInvitation>('/onboarding/preview', { method: 'POST', body: JSON.stringify({ token }) }),
  claim: (token: string) => request<EnterpriseInvitation>('/onboarding/claim', { method: 'POST', body: JSON.stringify({ token, confirmed: true }) }),
  mine: () => request<EnterpriseInvitation[]>('/onboarding/invitations', { cache: 'no-store' }),
}

const invitationKey = 'guanxian.enterprise.invitation'
const tokenPattern = /^[A-Za-z0-9_-]{43}$/
// Only a short-lived invitation capability is stored; never an access token or role.
export function captureInvitation(hash: string, storage: Storage = sessionStorage): string {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('invite')
  try {
    if (token !== null) {
      storage.removeItem(invitationKey)
      if (!tokenPattern.test(token)) return ''
      storage.setItem(invitationKey, JSON.stringify({ token, expires: Date.now() + 72 * 60 * 60 * 1000 }))
      return token
    }
    const saved = JSON.parse(storage.getItem(invitationKey) || 'null')
    if (saved && tokenPattern.test(saved.token) && typeof saved.expires === 'number' && saved.expires > Date.now()) return saved.token
    storage.removeItem(invitationKey)
  } catch { /* A link still works in-memory when browser storage is blocked. */ }
  return token && tokenPattern.test(token) ? token : ''
}
export function clearInvitation() {
  try { sessionStorage.removeItem(invitationKey) } catch { /* storage may be blocked */ }
}

export interface MyEnterprise { profile: MemberProfile; canEdit: boolean; etag: string }
async function ownEnterprise(options: RequestInit = {}): Promise<MyEnterprise> {
  let etag = ''
  const data = await request<Omit<MyEnterprise, 'etag'>>('/my-enterprise', { ...options, cache: 'no-store' },
    response => { etag = response.headers.get('ETag') || '' })
  if (!/^"\d+"$/.test(etag)) throw new Error('服务器未返回有效资料版本，暂不能安全编辑，请联系管理员。')
  return { ...data, etag }
}
export const myEnterpriseApi = {
  get: () => ownEnterprise(),
  update: (payload: MemberUpsertPayload, etag: string) => {
    if (!/^"\d+"$/.test(etag)) throw new Error('资料版本无效，请重新加载')
    return ownEnterprise({ method: 'PUT', headers: { 'If-Match': etag }, body: JSON.stringify(payload) })
  },
}
