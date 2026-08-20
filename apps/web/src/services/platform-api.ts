import { associationDashboard, collaborations, enterpriseDashboard, matches, members, policies } from '../mocks/data'
import type {
  Collaboration,
  DashboardData,
  EcosystemMatch,
  EnterpriseDashboardData,
  MemberEnterprise,
  MemberProfile,
  MemberUpsertPayload,
  Policy,
  VersionedMember,
} from '../types/domain'
import { ApiRequestError, request } from './http'

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

export const platformApi = {
  associationDashboard: () => request<DashboardData>('/dashboards/association', {}, mock(associationDashboard)),
  enterpriseDashboard: () => request<EnterpriseDashboardData>('/dashboards/enterprise', {}, mock(enterpriseDashboard)),
  members: () => request<MemberEnterprise[]>('/members', {}, mock(members)),
  member,
  updateMember,
  policies: () => request<Policy[]>('/policies', {}, mock(policies)),
  matches: () => request<EcosystemMatch[]>('/matches', {}, mock(matches)),
  collaborations: () => request<Collaboration[]>('/collaborations', {}, mock(collaborations)),
}
