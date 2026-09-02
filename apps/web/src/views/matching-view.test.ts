import { describe, expect, it } from 'vitest'
import type { EcosystemPage, PersistedMatch } from '../types/domain'
import { applyMatchPage, MATCH_STATE_FILTERS, reconcileSavedMatch } from './matching-view'

function match(id: string, state: string, version = 0): PersistedMatch {
  return { id, state, version } as PersistedMatch
}

describe('匹配列表服务端分页合同', () => {
  it('使用固定原始状态值，状态选项不依赖当前页是否恰好出现', () => {
    expect(MATCH_STATE_FILTERS[0]).toEqual({ value: '', label: '全部' })
    expect(MATCH_STATE_FILTERS.map(({ value }) => value)).toEqual([
      '', 'PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED', 'CONFIRMED',
      'INVITED', 'NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED', 'CLOSED',
    ])
  })

  it('以后端总数为准，并把收缩后的越界页定位到最后一页', () => {
    const result = applyMatchPage({
      items: [],
      total: 21,
      page: 9,
      size: 20,
    } as EcosystemPage<PersistedMatch>)
    expect(result).toMatchObject({ total: 21, page: 1, size: 20 })
  })

  it('状态变化后从当前筛选页移除记录并同步筛选总数', () => {
    const current = match('match-1', 'RECOMMENDED', 1)
    const saved = match('match-1', 'PARTIALLY_CONFIRMED', 2)
    const result = reconcileSavedMatch([current, match('match-2', 'RECOMMENDED')], 12, saved, 'RECOMMENDED')
    expect(result.items.map(({ id }) => id)).toEqual(['match-2'])
    expect(result.total).toBe(11)
  })

  it('全部状态页原位替换服务端返回的最新 allowedActions 和版本', () => {
    const current = { ...match('match-1', 'RECOMMENDED', 1), allowedActions: ['CONFIRM'] } as PersistedMatch
    const saved = { ...match('match-1', 'PARTIALLY_CONFIRMED', 2), allowedActions: [] } as PersistedMatch
    const result = reconcileSavedMatch([current], 1, saved, '')
    expect(result.items).toEqual([saved])
    expect(result.total).toBe(1)
  })
})
