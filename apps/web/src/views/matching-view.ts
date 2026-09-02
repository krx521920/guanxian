import type {
  EcosystemDemand,
  EcosystemPage,
  PersistedEcosystemMatch,
  SessionUser,
} from '../types/domain'

export const MATCH_FILTERS = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING_CONFIRMATION', label: '待确认' },
  { value: 'RECOMMENDED', label: '已推荐' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CLOSED', label: '已关闭' },
] as const

const writeRoles = new Set([
  'SYSTEM_ADMIN',
  'ASSOCIATION_ADMIN',
  'ASSOCIATION_OPERATOR',
  'ENTERPRISE_ADMIN',
])

export interface MatchSummary {
  total: number
  awaitingConfirmation: number
  confirmed: number
  closed: number
}

export type DemandPageLoader = (page: number, size: number) => Promise<EcosystemPage<EcosystemDemand>>

export function summarizeMatches(items: readonly PersistedEcosystemMatch[]): MatchSummary {
  return {
    total: items.length,
    awaitingConfirmation: items.filter((item) =>
      item.state === 'PENDING_CONFIRMATION' || item.state === 'RECOMMENDED').length,
    confirmed: items.filter((item) => item.state === 'CONFIRMED').length,
    closed: items.filter((item) => item.state === 'CLOSED').length,
  }
}

export function filterMatches(
  items: readonly PersistedEcosystemMatch[],
  state: string,
): PersistedEcosystemMatch[] {
  return state === 'ALL' ? [...items] : items.filter((item) => item.state === state)
}

export function normalizedScore(value: number): number {
  return Number.isFinite(value) ? Math.min(100, Math.max(0, Math.round(value))) : 0
}

export function scoreDashOffset(value: number): number {
  return 113 - normalizedScore(value) * 1.13
}

export function displayText(value: string | null | undefined, fallback: string): string {
  return value?.trim() || fallback
}

export function formatMatchTime(value: string): string {
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return '更新时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(timestamp))
}

export function canGenerateMatches(user: Pick<SessionUser, 'role' | 'permissions'> | null): boolean {
  return Boolean(user && (user.permissions.includes('ENTERPRISE_WRITE') || writeRoles.has(user.role)))
}

export async function loadOpenDemands(
  fetchPage: DemandPageLoader,
  pageSize = 100,
  maximumPages = 100,
): Promise<EcosystemDemand[]> {
  const size = Math.min(100, Math.max(1, Math.trunc(pageSize)))
  const byId = new Map<string, EcosystemDemand>()
  let page = 0

  while (page < maximumPages) {
    const result = await fetchPage(page, size)
    for (const item of result.items) byId.set(item.id, item)

    const responseSize = Math.max(1, result.size)
    const totalPages = Math.ceil(Math.max(0, result.total) / responseSize)
    if (page + 1 >= totalPages || result.items.length === 0) break
    page += 1
  }

  if (page >= maximumPages) {
    throw new Error('可见需求数量超过页面加载上限，请联系管理员提供服务端筛选能力。')
  }

  return [...byId.values()]
    .filter((item) => item.status === 'OPEN' && !item.disabled)
    .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
}
