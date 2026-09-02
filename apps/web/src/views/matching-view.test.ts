import { describe, expect, it, vi } from 'vitest'
import viewSource from './MatchingView.vue?raw'
import type { EcosystemDemand, EcosystemPage, PersistedEcosystemMatch, PersistedMatchState } from '../types/domain'
import {
  canGenerateMatches,
  displayText,
  filterMatches,
  loadOpenDemands,
  normalizedScore,
  scoreDashOffset,
  summarizeMatches,
} from './matching-view'

function match(state: PersistedMatchState, id: string = state): PersistedEcosystemMatch {
  return {
    id,
    demandId: 'demand-1',
    demandEnterpriseId: 'enterprise-1',
    candidateEnterpriseId: 'enterprise-2',
    demandCompany: '需求企业',
    demandTitle: '管线监测需求',
    scene: '燃气管网',
    supplierCompany: '供给企业',
    solution: '监测服务',
    score: 88,
    reasons: ['能力匹配'],
    state,
    closedReason: null,
    version: 2,
    updatedAt: '2026-08-31T01:00:00Z',
  }
}

function demand(id: string, status: string, disabled = false): EcosystemDemand {
  return {
    id,
    enterpriseId: 'enterprise-1',
    enterpriseName: '需求企业',
    title: `需求 ${id}`,
    description: '',
    scenarios: [],
    requiredCapabilities: [],
    visibility: 'MEMBERS',
    budgetMin: null,
    budgetMax: null,
    responseDeadline: null,
    status,
    closeReason: null,
    version: 0,
    disabled,
    updatedAt: `2026-08-${id === 'open-2' ? '31' : '30'}T01:00:00Z`,
  }
}

describe('matching view truthfulness', () => {
  it('derives every summary and filter from persisted English states', () => {
    const items = [
      match('PENDING_CONFIRMATION', 'm1'),
      match('RECOMMENDED', 'm2'),
      match('CONFIRMED', 'm3'),
      match('CLOSED', 'm4'),
    ]

    expect(summarizeMatches(items)).toEqual({ total: 4, awaitingConfirmation: 2, confirmed: 1, closed: 1 })
    expect(filterMatches(items, 'CONFIRMED').map((item) => item.id)).toEqual(['m3'])
    expect(filterMatches([], 'ALL')).toEqual([])
  })

  it('clamps only the visual score and preserves honest missing-value labels', () => {
    expect(normalizedScore(106)).toBe(100)
    expect(normalizedScore(Number.NaN)).toBe(0)
    expect(scoreDashOffset(-5)).toBe(113)
    expect(displayText('   ', '未展示')).toBe('未展示')
  })

  it('loads all demand pages and selects only real OPEN non-disabled records', async () => {
    const loader = vi.fn(async (page: number, size: number): Promise<EcosystemPage<EcosystemDemand>> => ({
      items: page === 0
        ? [demand('closed', 'CLOSED'), demand('open-1', 'OPEN')]
        : [demand('open-2', 'OPEN'), demand('disabled', 'OPEN', true)],
      total: 4,
      page,
      size,
    }))

    await expect(loadOpenDemands(loader, 2)).resolves.toEqual([
      demand('open-2', 'OPEN'),
      demand('open-1', 'OPEN'),
    ])
    expect(loader.mock.calls).toEqual([[0, 2], [1, 2]])
  })

  it('only enables generation for identities that map to the real write authority', () => {
    expect(canGenerateMatches({ role: 'ENTERPRISE_MEMBER', permissions: [] })).toBe(false)
    expect(canGenerateMatches({ role: 'ENTERPRISE_ADMIN', permissions: [] })).toBe(true)
    expect(canGenerateMatches({ role: 'ENTERPRISE_MEMBER', permissions: ['ENTERPRISE_WRITE'] })).toBe(true)
  })

  it('contains no fixed KPI/model claim and wires every rendered button', () => {
    expect(viewSource).not.toMatch(/\bAI\b|本月匹配建议|较上月|预计金额|860\s*万/)
    expect(viewSource).toContain('platformApi.generateMatches')
    expect(viewSource).toContain('platformApi.matches')
    for (const tag of viewSource.matchAll(/<button\b[^>]*>/g)) expect(tag[0]).toMatch(/@click=/)
  })
})
