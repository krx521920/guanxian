import { associationDashboard, collaborations, enterpriseDashboard, members, policies } from '../mocks/data'
import type {
  Collaboration,
  DashboardData,
  EcosystemDemand,
  EcosystemPage,
  EnterpriseDashboardData,
  MemberEnterprise,
  MemberImportCommitResult,
  MemberImportPreview,
  MemberProfile,
  MemberUpsertPayload,
  NotificationMessage,
  NotificationMessagePage,
  Policy,
  PersistedEcosystemMatch,
  VersionedMember,
} from '../types/domain'
import { ApiRequestError, request, requestBlob } from './http'

const strongEtag = /^"(0|[1-9][0-9]*)"$/
const safeRequestId = /^[A-Za-z0-9._:-]{1,128}$/

const mock = <T>(data: T) => async () => {
  await new Promise((resolve) => window.setTimeout(resolve, 120))
  return data
}

function requiredEtag(response: Response | null): string {
  const etag = response?.headers.get('ETag')
  if (etag && strongEtag.test(etag)) return etag

  const responseRequestId = response?.headers.get('X-Request-Id')
  const requestId = responseRequestId && safeRequestId.test(responseRequestId)
    ? responseRequestId
    : 'member-etag-contract'
  throw new ApiRequestError(
    '接口未返回有效的会员版本标识',
    requestId,
    response?.status,
    'MISSING_ETAG',
  )
}

async function member(id: string): Promise<VersionedMember> {
  let response: Response | null = null
  const data = await request<MemberProfile>(
    `/members/${encodeURIComponent(id)}`,
    {},
    undefined,
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

async function updateMember(
  id: string,
  payload: MemberUpsertPayload,
  etag: string,
): Promise<VersionedMember> {
  if (!strongEtag.test(etag)) {
    throw new ApiRequestError('会员版本标识无效，请重新加载', 'member-etag-client', undefined, 'INVALID_ETAG')
  }

  let response: Response | null = null
  const data = await request<MemberProfile>(
    `/members/${encodeURIComponent(id)}`,
    {
      method: 'PUT',
      headers: { 'If-Match': etag },
      body: JSON.stringify(payload),
    },
    undefined,
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

async function createMember(payload: MemberUpsertPayload): Promise<VersionedMember> {
  let response: Response | null = null
  const data = await request<MemberProfile>(
    '/members',
    { method: 'POST', body: JSON.stringify(payload) },
    undefined,
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

async function reviewMember(
  id: string,
  decision: 'ACTIVE' | 'INCOMPLETE' | 'DISABLED',
  comment: string,
  etag: string,
): Promise<VersionedMember> {
  if (!strongEtag.test(etag)) {
    throw new ApiRequestError('会员版本标识无效，请重新加载', 'member-etag-client', undefined, 'INVALID_ETAG')
  }
  let response: Response | null = null
  const data = await request<MemberProfile>(
    `/members/${encodeURIComponent(id)}/review`,
    {
      method: 'PUT',
      headers: { 'If-Match': etag },
      body: JSON.stringify({ decision, comment: comment.trim() || null }),
    },
    undefined,
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

function previewMemberImport(file: File): Promise<MemberImportPreview> {
  const form = new FormData()
  form.append('file', file, file.name)
  return request<MemberImportPreview>('/members/imports/preview', { method: 'POST', body: form })
}
export const platformApi = {
  associationDashboard: () => request<DashboardData>('/dashboards/association', {}, mock(associationDashboard)),
  enterpriseDashboard: () => request<EnterpriseDashboardData>('/dashboards/enterprise', {}, mock(enterpriseDashboard)),
  members: () => request<MemberEnterprise[]>('/members', {}, mock(members)),
  member,
  createMember,
  updateMember,
  reviewMember,
  downloadMemberTemplate: () => requestBlob('/members/import-template'),
  previewMemberImport,
  memberImportPreview: (batchId: string) => request<MemberImportPreview>(`/members/imports/${encodeURIComponent(batchId)}`),
  commitMemberImport: (batchId: string) => request<MemberImportCommitResult>(`/members/imports/${encodeURIComponent(batchId)}/commit`, { method: 'POST' }),
  policies: () => request<Policy[]>('/policies', {}, mock(policies)),
  matches: () => request<PersistedEcosystemMatch[]>('/matches'),
  matchingDemands: (page = 0, size = 100) => request<EcosystemPage<EcosystemDemand>>(
    `/demands?includeDeleted=false&page=${Math.max(0, page)}&size=${Math.min(100, Math.max(1, size))}`,
  ),
  generateMatches: (demandId: string, limit: number) => request<PersistedEcosystemMatch[]>(
    `/matches/demand/${encodeURIComponent(demandId)}/generate`,
    { method: 'POST', body: JSON.stringify({ limit: Math.min(20, Math.max(1, limit)) }) },
  ),
  collaborations: () => request<Collaboration[]>('/collaborations', {}, mock(collaborations)),
  notificationMessages: (query: {
    unreadOnly?: boolean
    status?: 'ARCHIVED'
    page?: number
    size?: number
  } = {}) => {
    const parameters = new URLSearchParams({
      unreadOnly: String(query.unreadOnly ?? false),
      page: String(query.page ?? 0),
      size: String(query.size ?? 20),
    })
    if (query.status) parameters.set('status', query.status)
    return request<NotificationMessagePage>(`/notifications/messages?${parameters.toString()}`)
  },
  markNotificationRead: (id: string) => request<NotificationMessage>(
    `/notifications/messages/${encodeURIComponent(id)}/read`,
    { method: 'PUT' },
  ),
}
