import type { EcosystemPage, MatchState, PersistedMatch } from '../types/domain'

export type MatchStateFilter = '' | MatchState

export const MATCH_STATE_FILTERS: ReadonlyArray<{ value: MatchStateFilter; label: string }> = [
  { value: '', label: '全部' },
  { value: 'PENDING_CONFIRMATION', label: '待协会推荐' },
  { value: 'RECOMMENDED', label: '待双方确认' },
  { value: 'PARTIALLY_CONFIRMED', label: '一方已确认' },
  { value: 'CONFIRMED', label: '双方已确认' },
  { value: 'INVITED', label: '已邀请' },
  { value: 'NEGOTIATING', label: '洽谈中' },
  { value: 'OUTCOME_PENDING', label: '成果待归档' },
  { value: 'ARCHIVED', label: '已归档' },
  { value: 'CLOSED', label: '已关闭' },
]

export function applyMatchPage(
  result: EcosystemPage<PersistedMatch>,
): EcosystemPage<PersistedMatch> {
  const size = Math.max(1, result.size)
  const total = Math.max(0, result.total)
  const lastPage = Math.max(0, Math.ceil(total / size) - 1)
  return {
    items: Array.isArray(result.items) ? result.items : [],
    total,
    page: Math.min(Math.max(0, result.page), lastPage),
    size,
  }
}

export function reconcileSavedMatch(
  items: readonly PersistedMatch[],
  total: number,
  saved: PersistedMatch,
  state: MatchStateFilter,
): { items: PersistedMatch[]; total: number } {
  const index = items.findIndex((item) => item.id === saved.id)
  if (index < 0) return { items: [...items], total }

  if (state && saved.state !== state) {
    return {
      items: items.filter((item) => item.id !== saved.id),
      total: Math.max(0, total - 1),
    }
  }

  return {
    items: items.map((item) => item.id === saved.id ? saved : item),
    total,
  }
}
