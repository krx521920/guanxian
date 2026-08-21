export const ROLES = [
  'SYSTEM_ADMIN',
  'ASSOCIATION_ADMIN',
  'ASSOCIATION_OPERATOR',
  'ENTERPRISE_ADMIN',
  'ENTERPRISE_MEMBER',
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
  type: 'policy' | 'match' | 'member' | 'task'
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
  status: '已认证' | '待完善' | '待审核' | '已停用'
  visibility: MemberVisibility
  canEdit: boolean
  canReview: boolean
  updatedAt: string
}

export type MemberStatus = 'ACTIVE' | 'PENDING_REVIEW' | 'INCOMPLETE' | 'DISABLED'
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
  introduction: string | null
  capabilities: string[]
  products: string[]
  cooperationNeeds: string[]
  visibility: MemberVisibility
  status: MemberStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface MemberUpsertPayload {
  name: string
  unifiedSocialCreditCode: string | null
  category: string
  address: string | null
  contactName: string | null
  contactPhone: string | null
  introduction: string | null
  capabilities: string[]
  products: string[]
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
  status: '现行有效' | '即将施行' | '征求意见'
  summary: string
  tags: string[]
}

export interface EcosystemMatch {
  id: string
  demandCompany: string
  demandTitle: string
  scene: string
  supplierCompany: string
  solution: string
  score: number
  reasons: string[]
  state: '待确认' | '已推荐' | '沟通中' | '已达成'
  updatedAt: string
}

export interface Collaboration {
  id: string
  title: string
  participants: string[]
  owner: string
  stage: '待受理' | '方案沟通' | '联合评估' | '已完成'
  priority: '高' | '中' | '低'
  nextAction: string
  dueDate: string
  progress: number
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
