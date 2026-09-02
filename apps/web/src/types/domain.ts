export const ROLES = [
  'SYSTEM_ADMIN',
  'ASSOCIATION_ADMIN',
  'ASSOCIATION_OPERATOR',
  'ENTERPRISE_ADMIN',
  'ENTERPRISE_MEMBER',
  'OBSERVER',
] as const

export type UserRole = (typeof ROLES)[number]

export interface SessionUser {
  id: string
  name: string
  role: UserRole
  organization: string
  title: string
  permissions: string[]
  associationId?: string | null
  enterpriseId?: string | null
}

export interface SystemAssociationOption {
  id: string
  name: string
}

export interface SystemEnterpriseOption {
  id: string
  associationId: string
  name: string
}

export type StatusTone = 'success' | 'warning' | 'info' | 'neutral' | 'danger'

export interface Metric {
  label: string
  value: string
  change: string
  tone: StatusTone
}

export interface Activity {
  id: string
  title: string
  detail: string
  time: string
  type: 'policy' | 'match' | 'member' | 'task' | 'collaboration'
}

export interface MemberEnterprise {
  id: string
  name: string
  shortName: string
  role: string
  scenes: string[]
  products: string[]
  city: string
  contact: string
  completeness: number
  status: '已认证' | '待完善' | '待审核' | '已停用' | '已删除'
  visibility: MemberVisibility
  canEdit: boolean
  canReview: boolean
  version: number
  updatedAt: string
  deletedAt: string | null
}

export type MemberStatus = 'ACTIVE' | 'PENDING_REVIEW' | 'INCOMPLETE' | 'DISABLED' | 'DELETED'
export type MemberVisibility = 'PRIVATE' | 'ASSOCIATION' | 'PARTNERS' | 'MEMBERS' | 'PUBLIC'

export interface MemberProfile {
  id: string
  associationId: string
  name: string
  unifiedSocialCreditCode: string | null
  category: string
  address: string | null
  contactName: string | null
  contactPhone: string | null
  contactEmail?: string | null
  introduction: string | null
  capabilities: string[]
  products: string[]
  services?: string[]
  applicationScenarios?: string[]
  cooperationNeeds: string[]
  visibility: MemberVisibility
  status: MemberStatus
  version: number
  createdAt: string
  updatedAt: string
  deletedAt: string | null
  deletedBySubject: string | null
  statusBeforeDelete: Exclude<MemberStatus, 'DELETED'> | null
}

export interface MemberUpsertPayload {
  name: string
  unifiedSocialCreditCode: string | null
  category: string
  address: string | null
  contactName: string | null
  contactPhone: string | null
  contactEmail?: string | null
  introduction: string | null
  capabilities: string[]
  products: string[]
  services?: string[]
  applicationScenarios?: string[]
  cooperationNeeds: string[]
  visibility: MemberVisibility
  status: MemberStatus
  associationId?: string
}

export type MemberImportRowData = Omit<MemberUpsertPayload, 'visibility' | 'status' | 'associationId'> & {
  visibility?: MemberVisibility | null
  status?: MemberStatus | null
  associationId?: string | null
}

export interface MemberImportRow {
  rowNumber: number
  data: MemberImportRowData
  errors: string[]
  status: 'VALID' | 'INVALID' | 'IMPORTED'
  enterpriseId: string | null
}

export interface MemberImportPreview {
  batchId: string
  filename: string
  templateVersion: string
  sourceSha256: string
  submittedUnit: string
  submittedEnterpriseId: string | null
  status: 'PREVIEWED' | 'COMMITTED' | 'CANCELLED'
  totalRows: number
  validRows: number
  invalidRows: number
  createdAt: string
  rows: MemberImportRow[]
}

export interface MemberImportCommitResult {
  batchId: string
  importedRows: number
  invalidRows: number
  enterpriseIds: string[]
}
export interface VersionedMember {
  member: MemberProfile
  etag: string
}

export interface Policy {
  id: string
  title: string
  authority: string
  level: '国家' | '北京市' | '行业协会'
  category: string
  publishDate: string
  effectiveDate: string | null
  status: string
  summary: string
  tags: string[]
  documentNumber?: string | null
  sourceUrl?: string | null
  associationId?: string | null
  visibility?: string
  version?: number
  disabled?: boolean
  deleted?: boolean
  updatedAt?: string
}

export interface PolicyUpsertPayload {
  associationId?: string | null
  title: string
  authority: string | null
  documentNumber: string | null
  level: string | null
  category: string | null
  publishDate: string | null
  effectiveDate: string | null
  sourceUrl: string | null
  summary: string | null
  tags: string[]
  visibility: string
}

export interface AccessBinding {
  id: string
  externalSubject: string | null
  username: string
  displayName: string
  email: string | null
  associationId: string | null
  associationName: string | null
  enterpriseId: string | null
  enterpriseName: string | null
  status: string
  version: number
  bound: boolean
  updatedAt: string
}

export interface AccessBindingPayload {
  externalSubject: string
  username: string
  displayName: string
  email: string | null
  associationId: string | null
  enterpriseId: string | null
}

export interface AccessBindingPage {
  items: AccessBinding[]
  total: number
  page: number
  size: number
}

export interface AuditRecord {
  id: number
  actorSubject: string
  actorUsername: string
  associationId: string | null
  enterpriseId: string | null
  action: string
  resourceType: string
  resourceId: string
  resourceVersion: number | null
  outcome: string
  details: Record<string, unknown>
  requestId: string
  occurredAt: string
}

export interface AuditPage {
  items: AuditRecord[]
  total: number
  page: number
  size: number
  snapshotId: number
}

export interface PolicyHistory {
  version: number
  action: string
  actorSubject: string
  snapshot: Record<string, unknown>
  occurredAt: string
}

export interface PolicyNotificationResult {
  policyId: string
  associationId: string
  recipientCount: number
  duplicate: boolean
}

export interface PolicyImpactAnalysis {
  id: string
  policyDocumentId: string
  policyTitle: string
  enterpriseId: string
  enterpriseName: string
  associationId: string
  impactLevel: string
  summary: string
  evidenceChunkIds: string[]
  status: string
  modelExecutionId: string | null
  reviewedBySubject: string | null
  reviewedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  analysisMethod: string
}

export interface PolicyImpactHistory {
  version: number
  action: string
  actorSubject: string
  snapshot: Record<string, unknown>
  occurredAt: string
}

export interface PolicyImpactPage {
  items: PolicyImpactAnalysis[]
  total: number
  page: number
  size: number
}

export interface KnowledgeIngestionResult {
  documentId: string
  documentVersionId: string
  version: number
  chunkCount: number
  contentHash: string
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingDimensions: number
}

export interface KnowledgeCitation {
  order: number
  documentId: string
  documentName: string
  version: number
  chunkId: string
  chunkIndex: number
  source: string | null
  sourceAttachmentId: string | null
  sourceFilename: string | null
  quote: string
  score: number
}

export interface PolicyQuestionAnswer {
  answer: string
  citations: KnowledgeCitation[]
  traceId: string
  mode: string
  retrievalMode: 'HYBRID_VECTOR' | 'LEXICAL'
  inputTokens: number
  outputTokens: number
  estimatedCost: number
}

export interface EcosystemMatch {
  id: string
  demandCompany: string | null
  demandTitle: string | null
  scene: string | null
  supplierCompany: string | null
  solution: string | null
  score: number | null
  reasons: string[]
  state: string | null
  updatedAt: string | null
}

export interface Collaboration {
  id: string
  title: string
  participants: string[]
  owner: string | null
  stage: string
  priority: string
  nextAction: string | null
  dueDate: string | null
  progress: number
  matchId?: string | null
  associationId?: string
  enterpriseId?: string | null
  version: number
  disabled: boolean
  deleted: boolean
  updatedAt: string
}

export interface CollaborationUpsertPayload {
  matchId?: string | null
  title: string
  participants: string[]
  owner: string | null
  priority: string | null
  nextAction: string | null
  dueDate: string | null
  progress: number
}

export interface EcosystemPage<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface Offering {
  id: string
  enterpriseId: string
  enterpriseName: string | null
  name: string
  kind: 'PRODUCT' | 'SERVICE'
  description: string | null
  scenarios: string[]
  qualifications: string[]
  visibility: string
  status: string
  version: number
  disabled: boolean
  deleted: boolean
  deletedAt: string | null
  updatedAt: string
  allowedActions: CatalogAction[]
}

export type KnowledgeDocumentStatus = 'DRAFT' | 'PENDING_REVIEW' | 'PUBLISHED' | 'DISABLED' | 'ARCHIVED'

export interface KnowledgeDocument {
  id: string
  associationId: string
  title: string
  documentType: string
  sourceType: string
  sourceUrl: string | null
  sourceFileId: string | null
  sourceFilename: string | null
  visibility: 'PUBLIC' | 'ASSOCIATION' | 'PRIVATE'
  status: KnowledgeDocumentStatus
  currentVersion: number
  chunkCount: number
  embeddingStatus: string
  lifecycleVersion: number
  createdBySubject: string
  createdAt: string
  updatedAt: string
  reviewedBySubject: string | null
  reviewedAt: string | null
  reviewComment: string | null
  deletedAt: string | null
  deletedBySubject: string | null
  deleted: boolean
}

export interface KnowledgeDocumentPage {
  items: KnowledgeDocument[]
  total: number
  page: number
  size: number
}

export interface KnowledgeReembeddingResult {
  documentId: string
  documentVersion: number
  chunkCount: number
  provider: string
  model: string
  dimensions: number
  lifecycleVersion: number
}

export type CatalogAction = 'UPDATE' | 'SUBMIT' | 'REVIEW' | 'DISABLE' | 'ENABLE' | 'DELETE' | 'RESTORE' | 'CLOSE'

export interface OfferingUpsertPayload {
  name: string
  kind: 'PRODUCT' | 'SERVICE'
  description: string | null
  scenarios: string[]
  qualifications: string[]
  visibility: string
}

export interface Demand {
  id: string
  enterpriseId: string
  enterpriseName: string | null
  title: string
  description: string
  scenarios: string[]
  requiredCapabilities: string[]
  visibility: string
  budgetMin: number | null
  budgetMax: number | null
  responseDeadline: string | null
  status: string
  closeReason: string | null
  version: number
  disabled: boolean
  deleted: boolean
  deletedAt: string | null
  updatedAt: string
  allowedActions: CatalogAction[]
}

export interface DemandUpsertPayload {
  title: string
  description: string
  scenarios: string[]
  requiredCapabilities: string[]
  visibility: string
  budgetMin: number | null
  budgetMax: number | null
  responseDeadline: string | null
}

export interface PersistedMatch extends EcosystemMatch {
  /** May be redacted for a cross-association reader when the sharing policy omits `state`. */
  state: MatchState | null
  demandId: string
  demandEnterpriseId: string
  candidateEnterpriseId: string
  recommendedAt: string | null
  demandConfirmedAt: string | null
  candidateConfirmedAt: string | null
  closedReason: string | null
  version: number
  allowedActions: MatchAction[]
}

export type MatchState =
  | 'PENDING_CONFIRMATION'
  | 'RECOMMENDED'
  | 'PARTIALLY_CONFIRMED'
  | 'CONFIRMED'
  | 'INVITED'
  | 'NEGOTIATING'
  | 'OUTCOME_PENDING'
  | 'ARCHIVED'
  | 'CLOSED'

export type MatchAction =
  | 'RECOMMEND'
  | 'CONFIRM'
  | 'INVITE'
  | 'NEGOTIATE'
  | 'FEEDBACK'
  | 'ARCHIVE'
  | 'CLOSE'

export type MatchNegotiationStage =
  | 'INITIAL_CONTACT'
  | 'TECHNICAL_EXCHANGE'
  | 'COMMERCIAL_NEGOTIATION'
  | 'CONTRACTING'
  | 'CONTRACT_SIGNED'
  | 'TERMINATED'

export interface MatchInvitation {
  id: string
  matchId: string
  senderEnterpriseId: string | null
  recipientEnterpriseId: string
  invitationType: 'ENTERPRISE' | 'ASSOCIATION_RECOMMENDATION'
  status: string
  message: string | null
  responseComment: string | null
  sentBySubject: string | null
  respondedBySubject: string | null
  expiresAt: string | null
  respondedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface MatchNegotiation {
  id: string
  matchId: string
  enterpriseId: string | null
  stage: string
  summary: string
  nextAction: string | null
  nextActionAt: string | null
  recordedBySubject: string | null
  createdAt: string
  version: number
}

export interface MatchFeedback {
  id: string
  matchId: string
  enterpriseId: string
  rating: number | null
  outcome: string
  closeReason: string | null
  comment: string | null
  submittedBySubject: string | null
  submittedAt: string
  version: number
  updatedAt: string
}

export interface MatchOutcome {
  id: string
  matchId: string
  title: string
  summary: string
  contractAmount: number | null
  resultType: string
  visibility: string
  archivedBySubject: string | null
  archivedAt: string
  version: number
}

export interface CollaborationActivity {
  id: number
  type: string
  detail: string
  actorSubject: string
  occurredAt: string
}

export interface CollaborationHistory {
  id: number
  version: number
  action: string
  actorSubject: string
  snapshot: Record<string, unknown>
  occurredAt: string
}

export interface Subscription {
  id: string
  userId: string
  associationId: string | null
  subscriptionType: string
  filters: Record<string, unknown>
  channels: string[]
  status: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface Attachment {
  id: string
  associationId: string | null
  enterpriseId: string | null
  originalFilename: string
  mediaType: string
  sizeBytes: number
  sha256: string
  scanStatus: string
  visibility: string
  status: string
  version: number
  uploadedAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface AttachmentPage {
  items: Attachment[]
  page: number
  size: number
  total: number
}

export interface AssociationAccessRequest {
  id: string
  applicantAssociationId: string
  targetAssociationId: string
  reason: string | null
  status: string
  requestedBySubject: string
  reviewedBySubject: string | null
  reviewComment: string | null
  requestedAt: string
  reviewedAt: string | null
  version: number
}

export interface AssociationRelationship {
  sourceAssociationId: string
  targetAssociationId: string
  status: string
  allowMemberData: boolean
  expiresAt: string | null
  suspendedAt: string | null
  suspendedByAssociationId: string | null
  suspendedBySubject: string | null
  revokedAt: string | null
  revokedBySubject: string | null
  revokeReason: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export type AssociationShareResourceType = 'MEMBER' | 'PRODUCT' | 'SERVICE' | 'DEMAND' | 'MATCH'

export interface AssociationSharePolicy {
  id: string
  sourceAssociationId: string
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  visibleFields: string[]
  status: 'ACTIVE' | 'SUSPENDED'
  validFrom: string
  expiresAt: string | null
  createdBySubject: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface AssociationSharePolicyPayload {
  sourceAssociationId?: string | null
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  visibleFields: string[]
  validFrom: string | null
  expiresAt: string | null
  status: 'ACTIVE' | 'SUSPENDED'
}

export interface AssociationConsent {
  id: string
  enterpriseId: string
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  resourceId: string
  status: string
  grantedBySubject: string
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
  version: number
}

export interface AssociationConsentTarget {
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  policyExpiresAt: string | null
}

export interface AssociationConsentPayload {
  enterpriseId?: string | null
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  resourceId: string
  expiresAt: string | null
}

export interface AssociationRecommendation {
  id: string
  sourceAssociationId: string
  targetAssociationId: string
  demandId: string | null
  matchId: string | null
  status: string
  summary: string
  createdBySubject: string
  reviewedBySubject: string | null
  reviewComment: string | null
  createdAt: string
  reviewedAt: string | null
  version: number
}

export interface AssociationPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface NotificationMessage {
  id: string
  userId: string
  associationId: string | null
  notificationType: string
  title: string
  body: string
  resourceType: string | null
  resourceId: string | null
  status: string
  readAt: string | null
  createdAt: string
  deliveredAt: string | null
}

export interface NotificationMessagePage {
  items: NotificationMessage[]
  total: number
  page: number
  size: number
}

export interface DashboardData {
  metrics: Metric[]
  activities: Activity[]
  sceneDistribution: Array<{ name: string; count: number; percent: number }>
  pendingTasks: Collaboration[]
}

export interface EnterpriseDashboardData {
  completeness: number
  metrics: Metric[]
  recommendedPolicies: Policy[]
  matches: EcosystemMatch[]
  todo: Collaboration[]
}
