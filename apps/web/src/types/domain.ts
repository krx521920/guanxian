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
  status: '已认证' | '待完善' | '待审核'
  updatedAt: string
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
