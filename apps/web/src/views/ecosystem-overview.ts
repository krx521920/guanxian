import { request } from '../services/http'

export interface EcosystemPage<T> {
  items: readonly T[]
  total: number
  page: number
  size: number
}

export interface OverviewOffering {
  id: string
  enterpriseId: string
  enterpriseName: string | null
  name: string
  kind: 'PRODUCT' | 'SERVICE'
  scenarios: readonly string[]
  status: string
  disabled: boolean
  updatedAt: string
}

export interface OverviewDemand {
  id: string
  enterpriseId: string
  enterpriseName: string | null
  title: string
  scenarios: readonly string[]
  status: string
  disabled: boolean
  updatedAt: string
}

export interface OverviewMatch {
  id: string
  demandId: string
  demandEnterpriseId: string
  candidateEnterpriseId: string
  demandCompany: string
  demandTitle: string
  scene?: string | null
  supplierCompany: string
  solution?: string | null
  score: number
  reasons: readonly string[]
  state: string
  closedReason?: string | null
  version: number
  updatedAt: string
}

export interface OverviewMember {
  id: string
}

export interface EcosystemOverviewSnapshot {
  members: readonly OverviewMember[]
  offerings: EcosystemPage<OverviewOffering>
  demands: EcosystemPage<OverviewDemand>
  matches: readonly OverviewMatch[]
}

export interface ScenarioSummary {
  name: string
  count: number
  percent: number
}

const sampleSize = 100

export type OverviewRequest = <T>(path: string) => Promise<T>

export async function loadEcosystemOverview(
  fetcher: OverviewRequest = request,
): Promise<EcosystemOverviewSnapshot> {
  const [members, offerings, demands, matches] = await Promise.all([
    fetcher<OverviewMember[]>('/members'),
    fetcher<EcosystemPage<OverviewOffering>>(`/offerings?query=&includeDeleted=false&page=0&size=${sampleSize}`),
    fetcher<EcosystemPage<OverviewDemand>>(`/demands?query=&includeDeleted=false&page=0&size=${sampleSize}`),
    fetcher<OverviewMatch[]>('/matches'),
  ])

  return { members, offerings, demands, matches }
}

export function summarizeScenarios(snapshot: EcosystemOverviewSnapshot, limit = 6): ScenarioSummary[] {
  const counts = new Map<string, number>()
  const scenarioGroups = [
    ...snapshot.offerings.items.filter((item) => !item.disabled).map((item) => item.scenarios),
    ...snapshot.demands.items.filter((item) => !item.disabled).map((item) => item.scenarios),
  ]

  for (const scenarios of scenarioGroups) {
    for (const rawName of new Set(scenarios)) {
      const name = rawName.trim()
      if (name) counts.set(name, (counts.get(name) ?? 0) + 1)
    }
  }

  const sorted = [...counts.entries()]
    .sort(([leftName, leftCount], [rightName, rightCount]) => rightCount - leftCount || leftName.localeCompare(rightName, 'zh-CN'))
    .slice(0, Math.max(0, limit))
  const maximum = sorted[0]?.[1] ?? 0

  return sorted.map(([name, count]) => ({
    name,
    count,
    percent: maximum > 0 ? Math.round((count / maximum) * 100) : 0,
  }))
}

export function hasOverviewData(snapshot: EcosystemOverviewSnapshot): boolean {
  return snapshot.members.length > 0
    || snapshot.offerings.total > 0
    || snapshot.demands.total > 0
    || snapshot.matches.length > 0
}

export function catalogSampleDescription(snapshot: EcosystemOverviewSnapshot): string {
  const loaded = snapshot.offerings.items.length + snapshot.demands.items.length
  const total = snapshot.offerings.total + snapshot.demands.total
  if (total === 0) return '当前权限范围内暂无产品、服务或需求档案。'
  if (loaded < total) return `场景分布基于已加载的 ${loaded} / ${total} 条可见档案。`
  return `场景分布基于当前可见的 ${total} 条档案。`
}
