import { request } from './http'
import type { EnterpriseInvitation } from './enterprise-onboarding'

export interface TeamMember {
  id: string; username: string; displayName: string; role: 'ENTERPRISE_MEMBER'
  status: 'ACTIVE' | 'INACTIVE' | 'NEEDS_REVIEW'; version: number; canDisable: boolean
}
export interface TeamPage<T> { items: T[]; total: number; page: number; size: number }
const base = '/my-enterprise/team'
function etag(version: number) {
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('版本无效，请重新加载成员列表')
  return { 'If-Match': `"${version}"` }
}
export const enterpriseTeamApi = {
  members: (page = 0) => request<TeamPage<TeamMember>>(`${base}/members?page=${page}`, { cache: 'no-store' }),
  invitations: (page = 0) => request<TeamPage<EnterpriseInvitation>>(`${base}/invitations?page=${page}`, { cache: 'no-store' }),
  // Enterprise and role are resolved by the server, never selected in the browser.
  invite: (username: string) => request<{ invitation: EnterpriseInvitation; token: string }>(`${base}/invitations`, {
    method: 'POST', body: JSON.stringify({ username: username.trim() }),
  }),
  revoke: (item: EnterpriseInvitation) => request<EnterpriseInvitation>(`${base}/invitations/${encodeURIComponent(item.id)}/revoke`, {
    method: 'PUT', headers: etag(item.version),
  }),
  disable: (member: TeamMember, note: string) => request<void>(`${base}/members/${encodeURIComponent(member.id)}/disable`, {
    method: 'PUT', headers: etag(member.version), body: JSON.stringify({ note: note.trim() }),
  }),
}
