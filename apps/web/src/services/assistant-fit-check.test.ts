import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from './http'
import { checkMemberFit, criteriaFromResult, normalizeFitCriteria, verifyFitResponse, type FitCriterion } from './assistant-fit-check'
import { businessFixture } from '../../tests/ui/business-fixtures'
import { businessResultText } from './assistant-business-results'
vi.mock('./http', () => ({ request: vi.fn() }))
const criteria: FitCriterion[] = [{ field: 'capabilities', value: '监测' }]
const fixture = () => {
  const result = businessFixture('MEMBER_FIT_CHECK')
  result.items = result.items.slice(0, 2); result.total = 2
  for (const item of result.items) item.evidence = [{ field: 'capabilities', criterion: '监测', observed: item.fields.capabilities, state: 'MATCHED', explanation: '登记字段支持' }]
  return { result, criteria }
}
describe('manual fit check contract', () => {
  beforeEach(() => vi.resetAllMocks())
  it.each([null, [], Array(9).fill(criteria[0]), [{ field: 'contactPhone', value: 'x' }], [{ field: 'capabilities', value: ' ' }],
    [{ field: 'capabilities', value: '字'.repeat(81) }], [{ field: 'status', value: 'YES' }],
    [{ field: 'products', value: ' ABC ' }, { field: 'products', value: 'abc' }]].map(value => ({ value })))('rejects malformed or duplicate conditions: $value', ({ value }) => {
    expect(() => normalizeFitCriteria(value)).toThrow()
  })
  it('trims values without changing user condition order or retaining client extras', () => {
    expect(normalizeFitCriteria([{ field: 'products', value: ' 平台 ', score: 100 }, { field: 'status', value: 'ACTIVE' }]))
      .toEqual([{ field: 'products', value: '平台' }, { field: 'status', value: 'ACTIVE' }])
  })
  it('prefills only a single consistent, supported condition set', () => {
    expect(criteriaFromResult(fixture().result)).toEqual(criteria)
    const changed = fixture(); changed.result.items[1].evidence[0].criterion = '其他条件'
    expect(criteriaFromResult(changed.result)).toEqual([])
    expect(criteriaFromResult(businessFixture())).toEqual([])
  })
  it('rejects changed IDs, order, conditions, result kinds or missing evidence', () => {
    const original = fixture(), ids = original.result.items.map(i => i.id)
    expect(verifyFitResponse(original, ids, criteria)).toEqual(original)
    for (const mutate of [
      (v: ReturnType<typeof fixture>) => { v.result.items.reverse() },
      (v: ReturnType<typeof fixture>) => { v.result.kind = 'RECOMMENDATIONS' },
      (v: ReturnType<typeof fixture>) => { v.result.items[0].evidence = [] },
      (v: ReturnType<typeof fixture>) => { v.result.items[0].evidence[0].criterion = '别的条件' },
      (v: ReturnType<typeof fixture>) => { v.result.items[0].evidence[0].observed = '与登记字段不同的资料' },
    ]) { const v = fixture(); mutate(v); expect(() => verifyFitResponse(v, ids, criteria)).toThrow() }
    expect(() => verifyFitResponse({ ...original, criteria: [{ field: 'products', value: '不同条件' }] }, ids, criteria)).toThrow()
  })
  it('denial is a valid failure receipt, never partial success', () => {
    const v = fixture(), ids = v.result.items.map(i => i.id)
    v.result.status = 'FORBIDDEN'; v.result.total = 0; v.result.items = []
    expect(verifyFitResponse(v, ids, criteria).result.status).toBe('FORBIDDEN')
    expect(businessResultText(v.result, false)).toContain('查询未成功')
    expect(businessResultText(v.result, false)).toContain('条件由用户手动确认')
  })
  it('sends only IDs, normalized conditions and organization; supports abort', async () => {
    const v = fixture(), ids = v.result.items.map(i => i.id), controller = new AbortController()
    vi.mocked(request).mockResolvedValue(v)
    await expect(checkMemberFit(ids, [{ field: 'capabilities', value: ' 监测 ' }], v.result.associationId!, controller.signal)).resolves.toEqual(v)
    const [path, options] = vi.mocked(request).mock.calls[0]
    expect(path).toBe('/assistant/members/fit-check'); expect(options?.signal).toBe(controller.signal)
    expect(JSON.parse(String(options?.body))).toEqual({ associationId: v.result.associationId, enterpriseIds: ids, criteria })
    await expect(checkMemberFit([], criteria)).rejects.toThrow(); expect(request).toHaveBeenCalledTimes(1)
  })
})
