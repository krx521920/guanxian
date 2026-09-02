import type {
  AccessBinding, AccessBindingPage,
  AccessBindingPayload,
  AssociationAccessRequest,
  AssociationPage,
  AssociationConsent,
  AssociationConsentPayload,
  AssociationConsentTarget,
  AssociationRecommendation,
  AssociationRelationship,
  AssociationSharePolicy,
  AssociationSharePolicyPayload,
  Attachment,
  AttachmentPage,
  AuditRecord,
  AuditPage,
  Collaboration,
  CollaborationActivity,
  CollaborationHistory,
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
  MatchNegotiationStage,
  MatchOutcome,
  MatchState,
  KnowledgeIngestionResult,
  KnowledgeDocument,
  KnowledgeDocumentPage,
  KnowledgeReembeddingResult,
  NotificationMessage,
  NotificationMessagePage,
  Offering,
  OfferingUpsertPayload,
  Policy,
  PolicyHistory,
  PolicyImpactAnalysis,
  PolicyImpactHistory,
  PolicyImpactPage,
  PolicyNotificationResult,
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
  accessBindings: () => request<AccessBinding[]>('/access-bindings'),
  accessBindingPage: (page = 0, size = 20) => request<AccessBindingPage>(`/access-bindings/page?page=${page}&size=${size}`),
  saveAccessBinding: (payload: AccessBindingPayload, version?: number) => request<AccessBinding>('/access-bindings', {
    method: 'POST',
    headers: version === undefined ? undefined : { 'If-Match': etag(version) },
    body: JSON.stringify(payload),
  }),
  disableAccessBinding: (item: AccessBinding) => request<AccessBinding>(`/access-bindings/${encodeURIComponent(item.id)}/disable`, { method: 'PUT', headers: { 'If-Match': etag(item.version) } }),
  restoreAccessBinding: (item: AccessBinding) => request<AccessBinding>(`/access-bindings/${encodeURIComponent(item.id)}/restore`, { method: 'PUT', headers: { 'If-Match': etag(item.version) } }),
  unbindAccessBinding: (item: AccessBinding) => request<AccessBinding>(`/access-bindings/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version) } }),
  auditLogs: (enterpriseId = '', limit = 200) => request<AuditRecord[]>(`/audit-logs?enterpriseId=${encodeURIComponent(enterpriseId)}&limit=${limit}`),
  auditLogPage: (enterpriseId = '', page = 0, size = 20, snapshotId?: number | null) => request<AuditPage>(
    `/audit-logs/page?enterpriseId=${encodeURIComponent(enterpriseId)}&page=${page}&size=${size}`
      + (snapshotId == null ? '' : `&snapshotId=${snapshotId}`),
  ),
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
  policies: (query = '', page = 0, size = 20, includeDeleted = false, level = '') => request<EcosystemPage<Policy>>(
    `/policies/page?q=${encodeURIComponent(query)}&level=${encodeURIComponent(level)}&page=${page}&size=${size}&includeDeleted=${includeDeleted}`,
  ),
  policyLevels: () => request<string[]>('/policies/levels'),
  policy: (id: string, includeDeleted = false) => request<Policy>(
    `/policies/${encodeURIComponent(id)}${includeDeleted ? '?includeDeleted=true' : ''}`,
  ),
  createPolicy: (payload: PolicyUpsertPayload) => request<Policy>('/policies', { method: 'POST', body: JSON.stringify(payload) }),
  updatePolicy: (id: string, payload: PolicyUpsertPayload, version: number) => request<Policy>(`/policies/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'If-Match': etag(version) }, body: JSON.stringify(payload) }),
  submitPolicy: (id: string, version: number) => request<Policy>(`/policies/${encodeURIComponent(id)}/submit`, { method: 'POST', headers: { 'If-Match': etag(version) } }),
  reviewPolicy: (id: string, version: number, approved: boolean, comment = '') => request<Policy>(`/policies/${encodeURIComponent(id)}/review`, { method: 'PUT', headers: { 'If-Match': etag(version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  disablePolicy: (item: Policy) => request<Policy>(`/policies/${encodeURIComponent(item.id)}/disable`, { method: 'PUT', headers: { 'If-Match': etag(item.version || 0) } }),
  deletePolicy: (item: Policy) => request<Policy>(`/policies/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version || 0) } }),
  restorePolicy: (item: Policy) => request<Policy>(`/policies/${encodeURIComponent(item.id)}/restore`, { method: 'PUT', headers: { 'If-Match': etag(item.version || 0) } }),
  policyHistory: (id: string, limit = 50) => request<PolicyHistory[]>(`/policies/${encodeURIComponent(id)}/history?limit=${limit}`),
  subscriptions: () => request<Subscription[]>('/notifications/subscriptions'),
  createSubscription: (payload: { subscriptionType: string; filters: Record<string, unknown>; channels: string[] }) => request<Subscription>('/notifications/subscriptions', { method: 'POST', body: JSON.stringify(payload) }),
  updateSubscription: (item: Subscription, payload: { subscriptionType: string; filters: Record<string, unknown>; channels: string[] }) => request<Subscription>(`/notifications/subscriptions/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  toggleSubscription: (item: Subscription) => request<Subscription>(`/notifications/subscriptions/${encodeURIComponent(item.id)}/${item.status === 'ACTIVE' ? 'disable' : 'restore'}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) } }),
  publishPolicyNotification: (item: Policy) => request<PolicyNotificationResult>('/notifications/policies', {
    method: 'POST',
    body: JSON.stringify({
      associationId: item.associationId || null,
      policyId: item.id,
      title: item.title,
      body: (item.summary || `政策《${item.title}》已发布，请查看详情。`).slice(0, 5000),
      idempotencyKey: `policy-release-${item.id}-${item.version || 0}`,
    }),
  }),
  policyImpacts: (
    page = 0,
    size = 20,
    filters: { status?: string; policyDocumentId?: string; enterpriseId?: string } = {},
  ) => {
    const query = new URLSearchParams({ page: String(page), size: String(size) })
    if (filters.status) query.set('status', filters.status)
    if (filters.policyDocumentId) query.set('policyDocumentId', filters.policyDocumentId)
    if (filters.enterpriseId) query.set('enterpriseId', filters.enterpriseId)
    return request<PolicyImpactPage>(`/policy-impact-analyses/page?${query}`)
  },
  policyImpact: (id: string) => request<PolicyImpactAnalysis>(`/policy-impact-analyses/${encodeURIComponent(id)}`),
  createPolicyImpact: (policyDocumentId: string, enterpriseId: string) => request<PolicyImpactAnalysis>(
    '/policy-impact-analyses',
    { method: 'POST', body: JSON.stringify({ policyDocumentId, enterpriseId }) },
  ),
  reanalyzePolicyImpact: (item: PolicyImpactAnalysis) => request<PolicyImpactAnalysis>(
    `/policy-impact-analyses/${encodeURIComponent(item.id)}/reanalyze`,
    { method: 'PUT', headers: { 'If-Match': etag(item.version) } },
  ),
  reviewPolicyImpact: (item: PolicyImpactAnalysis, approved: boolean, comment = '') => request<PolicyImpactAnalysis>(
    `/policy-impact-analyses/${encodeURIComponent(item.id)}/review`,
    {
      method: 'PUT',
      headers: { 'If-Match': etag(item.version) },
      body: JSON.stringify({ approved, comment: comment.trim() || null }),
    },
  ),
  policyImpactHistory: (id: string, limit = 50) => request<PolicyImpactHistory[]>(
    `/policy-impact-analyses/${encodeURIComponent(id)}/history?limit=${limit}`,
  ),
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
  transitionOffering: (item: Offering, action: 'submit' | 'disable') => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  enableOffering: (item: Offering) => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/enable`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  deleteOffering: (item: Offering) => request<Offering>(`/offerings/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version) } }),
  restoreOffering: (item: Offering) => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/restore`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  reviewOffering: (item: Offering, approved: boolean, comment = '') => request<Offering>(`/offerings/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  demands: (query = '', includeDeleted = false, page = 0, size = 20) => request<EcosystemPage<Demand>>(`/demands?query=${encodeURIComponent(query)}&includeDeleted=${includeDeleted}&page=${page}&size=${size}`),
  createDemand: (payload: DemandUpsertPayload) => request<Demand>('/demands', { method: 'POST', body: JSON.stringify(payload) }),
  updateDemand: (item: Demand, payload: DemandUpsertPayload) => request<Demand>(`/demands/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  transitionDemand: (item: Demand, action: 'submit' | 'disable') => request<Demand>(`/demands/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  enableDemand: (item: Demand) => request<Demand>(`/demands/${encodeURIComponent(item.id)}/enable`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  deleteDemand: (item: Demand) => request<Demand>(`/demands/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version) } }),
  restoreDemand: (item: Demand) => request<Demand>(`/demands/${encodeURIComponent(item.id)}/restore`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  reviewDemand: (item: Demand, approved: boolean, comment = '') => request<Demand>(`/demands/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  closeDemand: (item: Demand, reason: string) => request<Demand>(`/demands/${encodeURIComponent(item.id)}/close`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ reason }) }),

  matches: (page = 0, size = 20, state: MatchState | '' = '') => request<EcosystemPage<PersistedMatch>>(
    `/matches?page=${page}&size=${size}${state ? `&state=${encodeURIComponent(state)}` : ''}`,
  ),
  match: (id: string) => request<PersistedMatch>(`/matches/${encodeURIComponent(id)}`),
  matchGenerationDemands: (page = 0, size = 20) => request<EcosystemPage<Demand>>(
    `/matches/generation-demands?page=${page}&size=${size}`,
  ),
  matchesForDemand: (demandId: string) => request<PersistedMatch[]>(`/matches/demand/${encodeURIComponent(demandId)}`),
  generateMatches: (demandId: string, limit = 10) => request<PersistedMatch[]>(
    `/matches/demand/${encodeURIComponent(demandId)}/generate`,
    { method: 'POST', body: JSON.stringify({ limit }) },
    undefined,
    'json',
    60000,
  ),
  transitionMatch: (item: PersistedMatch, action: 'recommend' | 'confirm') => request<PersistedMatch>(`/matches/${encodeURIComponent(item.id)}/${action}`, { method: 'POST', headers: { 'If-Match': etag(item.version) } }),
  closeMatch: (item: PersistedMatch, reason: string) => request<PersistedMatch>(`/matches/${encodeURIComponent(item.id)}/close`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ reason }) }),
  matchInvitations: (matchId: string) => request<MatchInvitation[]>(`/matches/${encodeURIComponent(matchId)}/invitations`),
  inviteMatch: (item: PersistedMatch, invitationType: 'ENTERPRISE' | 'ASSOCIATION_RECOMMENDATION', message: string, expiresAt: string | null) => request<MatchInvitation>(`/matches/${encodeURIComponent(item.id)}/invitations`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ recipientEnterpriseId: item.candidateEnterpriseId, invitationType, message: message.trim() || null, expiresAt }) }),
  respondMatchInvitation: (item: MatchInvitation, accepted: boolean, comment: string) => request<MatchInvitation>(`/matches/invitations/${encodeURIComponent(item.id)}/respond`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ accepted, comment: comment.trim() || null }) }),
  matchNegotiations: (matchId: string) => request<MatchNegotiation[]>(`/matches/${encodeURIComponent(matchId)}/negotiations`),
  addMatchNegotiation: (item: PersistedMatch, payload: { stage: MatchNegotiationStage; summary: string; nextAction: string | null; nextActionAt: string | null }) => request<MatchNegotiation>(`/matches/${encodeURIComponent(item.id)}/negotiations`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  submitMatchFeedback: (
    item: PersistedMatch,
    payload: { rating: number | null; outcome: string; closeReason: string | null; comment: string | null },
    existing?: MatchFeedback | null,
  ) => request<MatchFeedback>(`/matches/${encodeURIComponent(item.id)}/feedback`, {
    method: 'POST',
    headers: existing ? { 'If-Match': etag(existing.version) } : undefined,
    body: JSON.stringify(payload),
  }),
  matchFeedback: (matchId: string) => request<MatchFeedback[]>(`/matches/${encodeURIComponent(matchId)}/feedback`),
  matchOutcomes: (matchId: string) => request<MatchOutcome[]>(`/matches/${encodeURIComponent(matchId)}/outcomes`),
  archiveMatchOutcome: (item: PersistedMatch, payload: { title: string; summary: string; contractAmount: number | null; resultType: string; visibility: string }) => request<MatchOutcome>(`/matches/${encodeURIComponent(item.id)}/outcomes`, { method: 'POST', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),

  collaborations: (query = '', page = 0, size = 20, includeDeleted = false, stage = '') => request<EcosystemPage<Collaboration>>(
    `/collaborations/page?query=${encodeURIComponent(query)}&stage=${encodeURIComponent(stage)}&page=${page}&size=${size}&includeDeleted=${includeDeleted}`,
  ),
  collaboration: (id: string, includeDeleted = false) => request<Collaboration>(
    `/collaborations/${encodeURIComponent(id)}?includeDeleted=${includeDeleted}`,
  ),
  createCollaboration: (payload: CollaborationUpsertPayload) => request<Collaboration>('/collaborations', { method: 'POST', body: JSON.stringify(payload) }),
  updateCollaboration: (item: Collaboration, payload: CollaborationUpsertPayload) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify(payload) }),
  submitCollaboration: (item: Collaboration) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/submit`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) } }),
  reviewCollaboration: (item: Collaboration, approved: boolean, comment = '') => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/review`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) }),
  transitionCollaboration: (item: Collaboration, targetStage: string, detail: string) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/transition`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) }, body: JSON.stringify({ targetStage, detail: detail.trim() || null }) }),
  disableCollaboration: (item: Collaboration) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/disable`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) } }),
  deleteCollaboration: (item: Collaboration) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version || 0) } }),
  restoreCollaboration: (item: Collaboration) => request<Collaboration>(`/collaborations/${encodeURIComponent(item.id)}/restore`, { method: 'POST', headers: { 'If-Match': etag(item.version || 0) } }),
  collaborationActivities: (id: string) => request<CollaborationActivity[]>(`/collaborations/${encodeURIComponent(id)}/activities`),
  addCollaborationActivity: (id: string, type: string, detail: string) => request<CollaborationActivity>(`/collaborations/${encodeURIComponent(id)}/activities`, { method: 'POST', body: JSON.stringify({ type, detail }) }),
  collaborationHistory: (id: string, limit = 100) => request<CollaborationHistory[]>(`/collaborations/${encodeURIComponent(id)}/history?limit=${limit}`),

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
    { method: 'POST', body: JSON.stringify({ attachmentId, title, associationId: associationId || null, documentType: 'POLICY', visibility: 'ASSOCIATION', status: 'DRAFT' }) },
    undefined,
    'json',
    60000,
  ),

  associationAccessRequests: () => request<AssociationAccessRequest[]>('/cross-associations/access-requests'),
  associationAccessRequestPage: (page = 0, size = 20) => request<AssociationPage<AssociationAccessRequest>>(`/cross-associations/access-requests/page?page=${page}&size=${size}`),
  createAssociationAccessRequest: (targetAssociationId: string, reason: string) => request<AssociationAccessRequest>('/cross-associations/access-requests', { method: 'POST', body: JSON.stringify({ targetAssociationId, reason: reason.trim() || null }) }),
  cancelAssociationAccessRequest: (item: AssociationAccessRequest, reason: string) => request<AssociationAccessRequest>(`/cross-associations/access-requests/${encodeURIComponent(item.id)}/cancel`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ reason: reason.trim() || null }) }),
  reviewAssociationAccessRequest: (
    item: AssociationAccessRequest,
    payload: { approved: boolean; comment: string; relationshipExpiresAt: string | null; allowMemberData: boolean },
  ) => request<AssociationAccessRequest>(`/cross-associations/access-requests/${encodeURIComponent(item.id)}/review`, {
    method: 'PUT',
    headers: { 'If-Match': etag(item.version) },
    body: JSON.stringify({
      decision: payload.approved ? 'APPROVE' : 'REJECT',
      comment: payload.comment.trim() || null,
      relationshipExpiresAt: payload.approved ? payload.relationshipExpiresAt : null,
      allowMemberData: payload.approved && payload.allowMemberData,
    }),
  }),
  associationRelationships: () => request<AssociationRelationship[]>('/cross-associations/relationships'),
  associationRelationshipPage: (page = 0, size = 20) => request<AssociationPage<AssociationRelationship>>(`/cross-associations/relationships/page?page=${page}&size=${size}`),
  changeAssociationRelationship: (item: AssociationRelationship, action: 'ACTIVATE' | 'SUSPEND' | 'REVOKE' | 'EXPIRE', reason: string) => request<AssociationRelationship>(`/cross-associations/relationships/${encodeURIComponent(item.sourceAssociationId)}/${encodeURIComponent(item.targetAssociationId)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ action, expiresAt: null, reason: reason.trim() || null }) }),
  associationSharePolicies: () => request<AssociationSharePolicy[]>('/cross-associations/share-policies'),
  associationSharePolicyPage: (page = 0, size = 20) => request<AssociationPage<AssociationSharePolicy>>(`/cross-associations/share-policies/page?page=${page}&size=${size}`),
  createAssociationSharePolicy: (payload: AssociationSharePolicyPayload) => request<AssociationSharePolicy>('/cross-associations/share-policies', { method: 'POST', body: JSON.stringify(payload) }),
  updateAssociationSharePolicy: (item: AssociationSharePolicy, payload: AssociationSharePolicyPayload) => request<AssociationSharePolicy>(`/cross-associations/share-policies/${encodeURIComponent(item.id)}`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify(payload) }),
  changeAssociationSharePolicyStatus: (item: AssociationSharePolicy, status: 'ACTIVE' | 'SUSPENDED') => request<AssociationSharePolicy>(`/cross-associations/share-policies/${encodeURIComponent(item.id)}/status`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ status }) }),
  associationConsents: () => request<AssociationConsent[]>('/cross-associations/consents'),
  associationConsentPage: (page = 0, size = 20) => request<AssociationPage<AssociationConsent>>(`/cross-associations/consents/page?page=${page}&size=${size}`),
  associationConsentTargets: () => request<AssociationConsentTarget[]>('/cross-associations/consent-targets'),
  grantAssociationConsent: (payload: AssociationConsentPayload) => request<AssociationConsent>('/cross-associations/consents', { method: 'POST', body: JSON.stringify(payload) }),
  revokeAssociationConsent: (item: AssociationConsent) => request<AssociationConsent>(`/cross-associations/consents/${encodeURIComponent(item.id)}`, { method: 'DELETE', headers: { 'If-Match': etag(item.version) } }),
  associationRecommendations: () => request<AssociationRecommendation[]>('/cross-associations/recommendations'),
  associationRecommendationPage: (page = 0, size = 20) => request<AssociationPage<AssociationRecommendation>>(`/cross-associations/recommendations/page?page=${page}&size=${size}`),
  createAssociationRecommendation: (targetAssociationId: string, demandId: string | null, matchId: string | null, summary: string) => request<AssociationRecommendation>('/cross-associations/recommendations', { method: 'POST', body: JSON.stringify({ targetAssociationId, demandId, matchId, summary: summary.trim() }) }),
  reviewAssociationRecommendation: (item: AssociationRecommendation, approved: boolean, comment: string) => request<AssociationRecommendation>(`/cross-associations/recommendations/${encodeURIComponent(item.id)}/review`, { method: 'PUT', headers: { 'If-Match': etag(item.version) }, body: JSON.stringify({ decision: approved ? 'APPROVE' : 'REJECT', comment: comment.trim() || null }) }),
  notifications: (options: { unreadOnly?: boolean; status?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams({
      unreadOnly: String(options.unreadOnly ?? false),
      page: String(options.page ?? 0),
      size: String(options.size ?? 20),
    })
    if (options.status) query.set('status', options.status)
    return request<NotificationMessagePage>(`/notifications/messages?${query}`)
  },
  markNotificationRead: (id: string) => request<NotificationMessage>(
    `/notifications/messages/${encodeURIComponent(id)}/read`,
    { method: 'PUT' },
  ),
  knowledgeDocuments: (includeDeleted = false, page = 0, size = 20) => request<KnowledgeDocumentPage>(
    `/knowledge/documents?includeDeleted=${includeDeleted}&page=${page}&size=${size}`,
  ),
  submitKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/submit`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } },
  ),
  reviewKnowledgeDocument: (item: KnowledgeDocument, approved: boolean, comment = '') => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/review`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) }, body: JSON.stringify({ approved, comment: comment.trim() || null }) },
  ),
  disableKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/disable`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } },
  ),
  archiveKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/archive`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } },
  ),
  removeKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}`,
    { method: 'DELETE', headers: { 'If-Match': etag(item.lifecycleVersion) } },
  ),
  restoreKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeDocument>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/restore`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } },
  ),
  reparseKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeIngestionResult>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/reparse`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } }, undefined, 'json', 60000,
  ),
  reembedKnowledgeDocument: (item: KnowledgeDocument) => request<KnowledgeReembeddingResult>(
    `/knowledge/documents/${encodeURIComponent(item.id)}/reembed`,
    { method: 'POST', headers: { 'If-Match': etag(item.lifecycleVersion) } }, undefined, 'json', 60000,
  ),
  archiveNotification: (id: string) => request<NotificationMessage>(
    `/notifications/messages/${encodeURIComponent(id)}/archive`,
    { method: 'PUT' },
  ),
  restoreNotification: (id: string) => request<NotificationMessage>(
    `/notifications/messages/${encodeURIComponent(id)}/restore`,
    { method: 'PUT' },
  ),
}
