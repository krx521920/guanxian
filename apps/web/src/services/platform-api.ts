import type {
  AssociationAccessRequest,
  AssociationConsent,
  AssociationConsentPayload,
  AssociationConsentTarget,
  AssociationRecommendation,
  AssociationRelationship,
  AssociationSharePolicy,
  AssociationSharePolicyPayload,
  Attachment,
  AttachmentPage,
  Collaboration,
  CollaborationActivity,
  CollaborationUpsertPayload,
  DashboardData,
  Demand,
  DemandUpsertPayload,
  EcosystemPage,
  EcosystemMatch,
  EnterpriseDashboardData,
  MemberEnterprise,
  MemberImportCommitResult,
  MemberImportPreview,
  MemberProfile,
  MemberUpsertPayload,
  MatchFeedback,
  MatchInvitation,
  MatchNegotiation,
  MatchOutcome,
  KnowledgeIngestionResult,
  NotificationMessage,
  NotificationMessagePage,
  Offering,
  OfferingUpsertPayload,
  Policy,
  PolicyImpactPage,
  PolicyQuestionAnswer,
  PolicyUpsertPayload,
  PersistedMatch,
  Subscription,
  SystemAssociationOption,
  SystemEnterpriseOption,
  VersionedMember,
} from '../types/domain'
import { ApiRequestError, request, requestBlob } from './http'

const strongEtag = /^"(0|[1-9][0-9]*)"$/
const safeRequestId = /^[A-Za-z0-9._:-]{1,128}$/

const etag = (version: number) => `"${version}"`

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

async function member(id: string, includeDeleted = false): Promise<VersionedMember> {
  let response: Response | null = null
  const data = await request<MemberProfile>(
    `/members/${encodeURIComponent(id)}${includeDeleted ? '?includeDeleted=true' : ''}`,
    {},
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
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

async function createMember(payload: MemberUpsertPayload): Promise<VersionedMember> {
  let response: Response | null = null
  const data = await request<MemberProfile>(
    '/members',
    { method: 'POST', body: JSON.stringify(payload) },
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
    (value) => { response = value },
  )
  return { member: data, etag: requiredEtag(response) }
}

function previewMemberImport(file: File, associationId?: string): Promise<MemberImportPreview> {
  const form = new FormData()
  form.append('file', file, file.name)
  const query = associationId ? `?associationId=${encodeURIComponent(associationId)}` : ''
  return request<MemberImportPreview>(
    `/members/imports/preview${query}`, { method: 'POST', body: form }, undefined, 'json', 60000,
  )
}
export const platformApi = {
  systemAssociations: () => request<SystemAssociationOption[]>('/system-context/associations'),
  systemEnterprises: (associationId: string) => request<SystemEnterpriseOption[]>(`/system-context/enterprises?associationId=${encodeURIComponent(associationId)}`),
  associationDashboard: () => request<DashboardData>('/dashboards/association'),
  enterpriseDashboard: () => request<EnterpriseDashboardData>('/dashboards/enterprise'),
  members: (query = '', status = '', page = 0, size = 20, includeDeleted = false) => request<EcosystemPage<MemberEnterprise>>(
    `/members/page?q=${encodeURIComponent(query)}&status=${encodeURIComponent(status)}&page=${page}&size=${size}&includeDeleted=${includeDeleted}`,
  ),
  member,
  createMember,
  updateMember,
  reviewMember,
  deleteMember: (id: string, version: number) => request<{ deleted: boolean; id: string; version: number }>(
    `/members/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'If-Match': etag(version) } },
  ),
  restoreMember: (id: string, version: number) => request<MemberProfile>(
    `/members/${encodeURIComponent(id)}/restore`, { method: 'PUT', headers: { 'If-Match': etag(version) } },
  ),
  downloadMemberTemplate: () => requestBlob('/members/import-template'),
  previewMemberImport,
  memberImportPreview: (batchId: string) => request<MemberImportPreview>(`/members/imports/${encodeURIComponent(batchId)}`),
  commitMemberImport: (batchId: string) => request<MemberImportCommitResult>(`/members/imports/${encodeURIComponent(batchId)}/commit`, { method: 'POST' }),
  policies: (query = '', page = 0, size = 20, includeDeleted = false) => request<EcosystemPage<Policy>>(
    `/policies/page?q=${encodeURIComponent(query)}&page=${page}&size=${size}&includeDeleted=${includeDeleted}`,
  ),
  policy: (id: string) => request<Policy>(`/policies/${encodeURIComponent(id)}`),
  createPolicy: (payload: PolicyUpsertPayload) => request<Policy>('/policies', { method: 'POST', body: JSON.stringify(payload) }),
  updatePolicy: (id: string, payload: PolicyUpsertPayload, version: number) => request<Policy>(`/policies/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'If-Match': etag(version) }, body: JSON.stringify(payload) }),
  submitPolicy: (id: string, version: number) => request<Policy>(`/policies/${encodeURIComponent(id)}/submit`, { method: 'POST', headers: { 'If-Match': etag(version) } }),
  reviewPolicy: (id: string, version: number, approved: boolean, comment = '') => request<Policy>(`/policies/${encodeURIComponent(id)}/review`, { method: 'PUT', headers: { 'If-Match': etag(version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  subscriptions: () => request<Subscription[]>('/notifications/subscriptions'),
  createSubscription: (payload: { subscriptionType: string; filters: Record<string, unknown>; channels: string[] }) => request<Subscription>('/notifications/subscriptions', { method: 'POST', body: JSON.stringify(payload) }),
  toggleSubscription: (item: Subscription) => request<Subscription>(`/notifications/subscriptions/${encodeURIComponent(item.id)}/${item.status === 'ACTIVE' ? 'disable' : 'restore'}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) } }),
  policyImpacts: (page = 0, size = 20) => request<PolicyImpactPage>(`/policy-impact-analyses/page?page=${page}&size=${size}`),
  askPolicyQuestion: (question: string, maxCitations = 5, associationId?: string) => request<PolicyQuestionAnswer>(
    '/knowledge/questions',
    { method: 'POST', body: JSON.stringify({ question, maxCitations, associationId: associationId || null }) },
    undefined,
    'json',
    60000,
  ),

  offerings: (query = '', includeDeleted = false, page = 0, size = 20) => request<EcosystemPage<Offering>>(`/offerings?query=${encodeURIComponent(query)}&includeDeleted=${includeDeleted}&page=${page}&size=${size}`),
  createOffering: (payload: OfferingUpsertPayload) => request<Offering>('/offerings', { method: 'POST', body: JSON.stringify(payload) }),
  updateOffering: (item: Offering, payload: OfferingUpsertPayload) => request<Offering>(`/offerings/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  transitionOffering: (item: Offering, action: 'submit' | 'disable' | 'restore') => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  reviewOffering: (item: Offering, approved: boolean, comment = '') => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  demands: (query = '', includeDeleted = false, page = 0, size = 20) => request<EcosystemPage<Demand>>(`/demands?query=${encodeURIComponent(query)}&includeDeleted=${includeDeleted}&page=${page}&size=${size}`),
  createDemand: (payload: DemandUpsertPayload) => request<Demand>('/demands', { method: 'POST', body: JSON.stringify(payload) }),
  updateDemand: (item: Demand, payload: DemandUpsertPayload) => request<Demand>(`/demands/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  transitionDemand: (item: Demand, action: 'submit' | 'disable' | 'restore') => request<Demand>(`/demands/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  reviewDemand: (item: Demand, approved: boolean, comment = '') => request<Demand>(`/demands/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  closeDemand: (item: Demand, reason: string) => request<Demand>(`/demands/${encodeURIComponent(item.id)}/close`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ reason }) }),

  matches: () => request<PersistedMatch[]>('/matches'),
  matchesForDemand: (demandId: string) => request<PersistedMatch[]>(`/matches/demand/${encodeURIComponent(demandId)}`),
  generateMatches: (demandId: string, limit = 10) => request<PersistedMatch[]>(`/matches/demand/${encodeURIComponent(demandId)}/generate`, { method: 'POST', body: JSON.stringify({ limit }) }),
  transitionMatch: (item: PersistedMatch, action: 'recommend' | 'confirm') => request<PersistedMatch>(`/matches/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  closeMatch: (item: PersistedMatch, reason: string) => request<PersistedMatch>(`/matches/${encodeURIComponent(item.id)}/close`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ reason }) }),
  matchInvitations: (matchId: string) => request<MatchInvitation[]>(`/matches/${encodeURIComponent(matchId)}/invitations`),
  inviteMatch: (item: PersistedMatch, invitationType: 'ENTERPRISE' | 'ASSOCIATION_RECOMMENDATION', message: string, expiresAt: string | null) => request<MatchInvitation>(`/matches/${encodeURIComponent(item.id)}/invitations`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ recipientEnterpriseId: item.candidateEnterpriseId, invitationType, message: message.trim() || null, expiresAt }) }),
  respondMatchInvitation: (item: MatchInvitation, accepted: boolean, comment: string) => request<MatchInvitation>(`/matches/invitations/${encodeURIComponent(item.id)}/respond`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ accepted, comment: comment.trim() || null }) }),
  matchNegotiations: (matchId: string) => request<MatchNegotiation[]>(`/matches/${encodeURIComponent(matchId)}/negotiations`),
  addMatchNegotiation: (item: PersistedMatch, payload: { stage: string; summary: string; nextAction: string | null; nextActionAt: string | null }) => request<MatchNegotiation>(`/matches/${encodeURIComponent(item.id)}/negotiations`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  submitMatchFeedback: (matchId: string, payload: { rating: number | null; outcome: string; closeReason: string | null; comment: string | null }) => request<MatchFeedback>(`/matches/${encodeURIComponent(matchId)}/feedback`, { method: 'POST', body: JSON.stringify(payload) }),
  matchFeedback: (matchId: string) => request<MatchFeedback[]>(`/matches/${encodeURIComponent(matchId)}/feedback`),
  matchOutcomes: (matchId: string) => request<MatchOutcome[]>(`/matches/${encodeURIComponent(matchId)}/outcomes`),
  archiveMatchOutcome: (item: PersistedMatch, payload: { title: string; summary: string; contractAmount: number | null; resultType: string; visibility: string }) => request<MatchOutcome>(`/matches/${encodeURIComponent(item.id)}/outcomes`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),

  collaborations: (query = '', page = 0, size = 20, includeDeleted = false) => request<EcosystemPage<Collaboration>>(
    `/collaborations/page?query=${encodeURIComponent(query)}&page=${page}&size=${size}&includeDeleted=${includeDeleted}`,
  ),
  collaboration: (id: string) => request<Collaboration>(`/collaborations/${encodeURIComponent(id)}`),
  createCollaboration: (payload: CollaborationUpsertPayload) => request<Collaboration>('/collaborations', { method: 'POST', body: JSON.stringify(payload) }),
  updateCollaboration: (item: Collaboration, payload: CollaborationUpsertPayload) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify(payload) }),
  submitCollaboration: (item: Collaboration) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/submit`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) } }),
  reviewCollaboration: (item: Collaboration, approved: boolean, comment = '') => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  transitionCollaboration: (item: Collaboration, targetStage: string, detail: string) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/transition`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify({ targetStage, detail: detail.trim() || null }) }),
  collaborationActivities: (id: string) => request<CollaborationActivity[]>(`/collaborations/${encodeURIComponent(id)}/activities`),
  addCollaborationActivity: (id: string, type: string, detail: string) => request<CollaborationActivity>(`/collaborations/${encodeURIComponent(id)}/activities`, { method: 'POST', body: JSON.stringify({ type, detail }) }),

  attachments: (enterpriseId?: string, includeDeleted = false, page = 0, size = 20) => request<AttachmentPage>(`/attachments?page=${page}&size=${size}&includeDeleted=${includeDeleted}${enterpriseId ? `&enterpriseId=${encodeURIComponent(enterpriseId)}` : ''}`),
  uploadAttachment: (file: File, visibility = 'PRIVATE', enterpriseId?: string, associationId?: string) => {
    const form = new FormData(); form.append('file', file, file.name)
    const query = new URLSearchParams({ visibility }); if (enterpriseId) query.set('enterpriseId', enterpriseId); if (associationId) query.set('associationId', associationId)
    return request<Attachment>(`/attachments?${query}`, { method: 'POST', body: form }, undefined, 'json', 60000)
  },
  downloadAttachment: (id: string) => requestBlob(`/attachments/${encodeURIComponent(id)}/content`, {}, 60000),
  removeAttachment: (item: Attachment) => request<Attachment>(`/attachments/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version) } }),
  restoreAttachment: (item: Attachment) => request<Attachment>(`/attachments/${encodeURIComponent(item.id)}/restore`, { method: 'PUT', headers: { 'If-Match': etag(item.version) } }),
  ingestKnowledgeFile: (attachmentId: string, title: string, associationId?: string) => request<KnowledgeIngestionResult>(
    '/knowledge/documents/file',
    { method: 'POST', body: JSON.stringify({ attachmentId, title, associationId: associationId || null, documentType: 'POLICY', visibility: 'ASSOCIATION', status: 'PUBLISHED' }) },
    undefined,
    'json',
    60000,
  ),

  associationAccessRequests: () => request<AssociationAccessRequest[]>('/cross-associations/access-requests'),
  createAssociationAccessRequest: (targetAssociationId: string, reason: string) => request<AssociationAccessRequest>('/cross-associations/access-requests', { method: 'POST', body: JSON.stringify({ targetAssociationId, reason: reason.trim() || null }) }),
  cancelAssociationAccessRequest: (item: AssociationAccessRequest, reason: string) => request<AssociationAccessRequest>(`/cross-associations/access-requests/${encodeURIComponent(item.id)}/cancel`, { method: 'PUT', body: JSON.stringify({ reason: reason.trim() || null }) }),
  reviewAssociationAccessRequest: (
    item: AssociationAccessRequest,
    payload: { approved: boolean; comment: string; relationshipExpiresAt: string | null; allowMemberData: boolean },
  ) => request<AssociationAccessRequest>(`/cross-associations/access-requests/${encodeURIComponent(item.id)}/review`, {
    method: 'PUT',
    body: JSON.stringify({
      decision: payload.approved ? 'APPROVE' : 'REJECT',
      comment: payload.comment.trim() || null,
      relationshipExpiresAt: payload.approved ? payload.relationshipExpiresAt : null,
      allowMemberData: payload.approved && payload.allowMemberData,
    }),
  }),
  associationRelationships: () => request<AssociationRelationship[]>('/cross-associations/relationships'),
  changeAssociationRelationship: (item: AssociationRelationship, action: 'ACTIVATE' | 'SUSPEND' | 'REVOKE' | 'EXPIRE', reason: string) => request<AssociationRelationship>(`/cross-associations/relationships/${encodeURIComponent(item.sourceAssociationId)}/${encodeURIComponent(item.targetAssociationId)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ action, expiresAt: null, reason: reason.trim() || null }) }),
  associationSharePolicies: () => request<AssociationSharePolicy[]>('/cross-associations/share-policies'),
  createAssociationSharePolicy: (payload: AssociationSharePolicyPayload) => request<AssociationSharePolicy>('/cross-associations/share-policies', { method: 'POST', body: JSON.stringify(payload) }),
  updateAssociationSharePolicy: (item: AssociationSharePolicy, payload: AssociationSharePolicyPayload) => request<AssociationSharePolicy>(`/cross-associations/share-policies/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  changeAssociationSharePolicyStatus: (item: AssociationSharePolicy, status: 'ACTIVE' | 'SUSPENDED') => request<AssociationSharePolicy>(`/cross-associations/share-policies/${encodeURIComponent(item.id)}/status`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ status }) }),
  associationConsents: () => request<AssociationConsent[]>('/cross-associations/consents'),
  associationConsentTargets: () => request<AssociationConsentTarget[]>('/cross-associations/consent-targets'),
  grantAssociationConsent: (payload: AssociationConsentPayload) => request<AssociationConsent>('/cross-associations/consents', { method: 'POST', body: JSON.stringify(payload) }),
  revokeAssociationConsent: (item: AssociationConsent) => request<AssociationConsent>(`/cross-associations/consents/${encodeURIComponent(item.id)}`, { method: 'DELETE' }),
  associationRecommendations: () => request<AssociationRecommendation[]>('/cross-associations/recommendations'),
  createAssociationRecommendation: (targetAssociationId: string, demandId: string | null, matchId: string | null, summary: string) => request<AssociationRecommendation>('/cross-associations/recommendations', { method: 'POST', body: JSON.stringify({ targetAssociationId, demandId, matchId, summary: summary.trim() }) }),
  reviewAssociationRecommendation: (item: AssociationRecommendation, approved: boolean, comment: string) => request<AssociationRecommendation>(`/cross-associations/recommendations/${encodeURIComponent(item.id)}/review`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ decision: approved ? 'APPROVE' : 'REJECT', comment: comment.trim() || null }) }),
  notifications: (unreadOnly = false, page = 0, size = 20) => request<NotificationMessagePage>(
    `/notifications/messages?unreadOnly=${unreadOnly}&page=${page}&size=${size}`,
  ),
  markNotificationRead: (id: string) => request<NotificationMessage>(
    `/notifications/messages/${encodeURIComponent(id)}/read`,
    { method: 'PUT' },
  ),
}
