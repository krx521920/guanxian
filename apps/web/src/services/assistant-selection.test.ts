import { describe, expect, it, vi } from 'vitest'
import { businessFixture } from '../../tests/ui/business-fixtures'
import { selectedEnterpriseIds, followupSelection, businessResultText } from './assistant-business-results'
import { assistantStreamConsumer } from './assistant-stream'

const first = businessFixture().items[0], second = businessFixture().items[1]
const selected = () => ({ ...businessFixture('SELECTED_MEMBERS'), items: [first, second], total: 2 })
const event = (type: string, data = {}) => ({ type, conversationId: 'fixture', ...data })
const status = (results: unknown[]) => event('status', { status: { phase: 'GENERATING', mode: 'SPRING_AI_AGENT' }, businessResults: results })

describe('explicit business follow-up scope', () => {
  it.each([[first.id, first.id], ['javascript:bad'], [null], Array(5).fill(first.id)].map(value => ({ value })))('rejects invalid IDs $value', ({ value }) => {
    expect(() => selectedEnterpriseIds(value)).toThrow('INVALID_ENTERPRISE_SELECTION')
  })
  it('keeps selected order and sends no snapshot fields or contacts as authorization', () => {
    const selection = followupSelection([second, first])
    expect(selection).toEqual([{ id: second.id, name: second.name }, { id: first.id, name: first.name }])
    expect(selectedEnterpriseIds(undefined)).toEqual([])
  })
  it('requires the matching selected receipt before displaying any text', async () => {
    const delta = vi.fn()
    const stream = assistantStreamConsumer('fixture', delta, undefined, undefined, [first.id, second.id])
    await stream.accept(event('start'))
    await expect(stream.accept(event('delta', { delta: 'old server ignored the IDs' }))).rejects.toMatchObject({ code: 'UNVERIFIED_ENTERPRISE_SELECTION' })
    expect(delta).not.toHaveBeenCalled()
  })
  it('rejects changed object order and unrelated lookup results', async () => {
    const stream = assistantStreamConsumer('fixture', vi.fn(), undefined, undefined, [first.id, second.id])
    await stream.accept(event('start'))
    await expect(stream.accept(status([{ ...selected(), items: [second, first] }]))).rejects.toMatchObject({ code: 'UNVERIFIED_ENTERPRISE_SELECTION' })
  })
  it('accepts verified selection and requires it on terminal completion too', async () => {
    const delta = vi.fn(), receive = vi.fn()
    const stream = assistantStreamConsumer('fixture', delta, undefined, receive, [first.id, second.id])
    await stream.accept(event('start')); await stream.accept(status([selected()]))
    await stream.accept(event('delta', { delta: '已核对' }))
    const answer = { answer: '已核对', conversationId: 'fixture', mode: 'SPRING_AI_AGENT', modelConnected: true, traceId: 'fixture', citations: [] }
    await expect(stream.accept(event('complete', { answer }))).rejects.toMatchObject({ code: 'UNVERIFIED_ENTERPRISE_SELECTION' })
    expect(await stream.accept(event('complete', { answer: { ...answer, businessResults: [selected()] } }))).toBe(true)
    expect(delta).toHaveBeenCalledWith('已核对')
  })
  it('allows honest denied local responses but not claimed model success after denial', async () => {
    const denied = { ...selected(), status: 'FORBIDDEN', total: 0, items: [] }
    const stream = assistantStreamConsumer('fixture', vi.fn(), undefined, undefined, [first.id, second.id])
    await stream.accept(event('start')); await stream.accept(status([denied])); await stream.accept(event('delta', { delta: '无权限' }))
    const answer = { answer: '无权限', conversationId: 'fixture', mode: 'LOCAL_BUSINESS_QUERY', modelConnected: true, traceId: 'fixture', citations: [], businessResults: [denied] }
    await expect(stream.accept(event('complete', { answer }))).rejects.toMatchObject({ code: 'UNVERIFIED_ENTERPRISE_SELECTION' })
    expect(await stream.accept(event('complete', { answer: { ...answer, modelConnected: false } }))).toBe(true)
  })
})

describe('copyable business evidence', () => {
  it('copies scope, filters, counts, immutable IDs and missing-data qualifiers', () => {
    const text = businessResultText(businessFixture('RECOMMENDATIONS'), true)
    expect(text).toContain('未完成回答中的已返回查询')
    expect(text).toContain('查询范围：'); expect(text).toContain('查询时间：'); expect(text).toContain('查询编号：')
    expect(text).toContain(`查询组织上下文：${businessFixture().associationId}`)
    expect(text).toContain(first.id); expect(text).toContain('资料不足'); expect(text).toContain('不代表当前最新状态')
    expect(text).toContain('条件来自本轮工具参数')
    expect(text).not.toContain('contactPhone')
  })
  it('does not turn errors into empty successful results or silently export bad data', () => {
    expect(businessResultText({ ...businessFixture(), status: 'FAILED', total: 0, items: [] }, false)).toContain('查询未成功')
    expect(() => businessResultText({ ...businessFixture(), total: -1 }, false)).toThrow()
  })
})
