import { describe, expect, it } from 'vitest'
import viewSource from './EcosystemOverviewView.vue?raw'
import loaderSource from './ecosystem-overview.ts?raw'
import {
  catalogSampleDescription,
  hasOverviewData,
  loadEcosystemOverview,
  summarizeScenarios,
  type EcosystemOverviewSnapshot,
} from './ecosystem-overview'

function snapshot(): EcosystemOverviewSnapshot {
  return {
    members: [],
    offerings: {
      items: [
        { id: 'o-1', enterpriseId: 'e-1', enterpriseName: '甲企业', name: '监测服务', kind: 'SERVICE', scenarios: ['燃气', '燃气', '排水'], status: 'ACTIVE', disabled: false, updatedAt: '2026-08-30T00:00:00Z' },
        { id: 'o-2', enterpriseId: 'e-2', enterpriseName: '乙企业', name: '停用产品', kind: 'PRODUCT', scenarios: ['供热'], status: 'DISABLED', disabled: true, updatedAt: '2026-08-29T00:00:00Z' },
      ],
      total: 3,
      page: 0,
      size: 100,
    },
    demands: {
      items: [
        { id: 'd-1', enterpriseId: 'e-3', enterpriseName: '丙企业', title: '燃气改造', scenarios: ['燃气', '更新改造'], status: 'OPEN', disabled: false, updatedAt: '2026-08-28T00:00:00Z' },
      ],
      total: 2,
      page: 0,
      size: 100,
    },
    matches: [],
  }
}

describe('ecosystem overview', () => {
  it('derives scenario distribution only from visible non-disabled records', () => {
    expect(summarizeScenarios(snapshot())).toEqual([
      { name: '燃气', count: 2, percent: 100 },
      { name: '更新改造', count: 1, percent: 50 },
      { name: '排水', count: 1, percent: 50 },
    ])
  })

  it('reports partial catalog samples honestly and detects real data', () => {
    const data = snapshot()
    expect(hasOverviewData(data)).toBe(true)
    expect(catalogSampleDescription(data)).toBe('场景分布基于已加载的 3 / 5 条可见档案。')
  })

  it('does not reintroduce fixed realtime, AI or member-count claims and wires every button', () => {
    expect(viewSource).not.toMatch(/106\s*家|\bAI\b|实时|trialAreas|gridCells/)
    expect(viewSource).toContain('loadEcosystemOverview')
    expect(viewSource).toContain('<AsyncResourceState')
    expect(viewSource).toContain('v-if="!hasEcosystemData"')

    for (const match of viewSource.matchAll(/<button\b[^>]*>/g)) {
      expect(match[0]).toMatch(/@click=/)
    }
  })

  it('loads each overview metric from a real protected API without a mock fallback', () => {
    expect(loaderSource).toContain("fetcher<OverviewMember[]>('/members')")
    expect(loaderSource).toContain('fetcher<EcosystemPage<OverviewOffering>>(`/offerings?')
    expect(loaderSource).toContain('fetcher<EcosystemPage<OverviewDemand>>(`/demands?')
    expect(loaderSource).toContain("fetcher<OverviewMatch[]>('/matches')")
    expect(loaderSource).not.toMatch(/mocks\/data|mockFallback|fallback/i)
  })

  it('keeps an empty persisted match list empty and requests the authorized match endpoint', async () => {
    const paths: string[] = []
    const emptyPage = { items: [], total: 0, page: 0, size: 100 }
    const fetcher = async <T>(path: string): Promise<T> => {
      paths.push(path)
      if (path === '/members' || path === '/matches') return [] as T
      return emptyPage as T
    }

    const result = await loadEcosystemOverview(fetcher)

    expect(paths).toContain('/matches')
    expect(result.matches).toEqual([])
    expect(hasOverviewData(result)).toBe(false)
  })
})
