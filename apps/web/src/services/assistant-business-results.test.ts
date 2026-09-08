import { describe, expect, it, vi } from 'vitest'
import { businessFixture, fixtureMembers } from '../../tests/ui/business-fixtures'
import { parseBusinessResults, memberItem, fieldValue } from './assistant-business-results'
import { assistantStreamConsumer } from './assistant-stream'

describe('server business data contract', () => {
  it('accepts bounded receipts including global context, retains true total, and handles older servers', () => {
    expect(parseBusinessResults(undefined)).toEqual([])
    const receipt = businessFixture()
    expect(parseBusinessResults([receipt])[0]).toMatchObject({ total: 12, items: receipt.items })
    expect(parseBusinessResults([{ ...receipt, associationId: null }])).toHaveLength(1)
  })
  it('normalizes an omitted nullable scope from NON_NULL servers without changing the receipt or granting authority', () => {
    const { associationId: _scope, ...wire } = businessFixture()
    const parsed = parseBusinessResults([wire])[0]
    expect(parsed.associationId).toBeNull()
    expect(parsed.scope).toBe(wire.scope)
    expect(parsed.items).toEqual(wire.items)
    expect(wire).not.toHaveProperty('associationId')
    expect(parseBusinessResults([{ ...wire, status: 'FORBIDDEN', total: 0, items: [] }])[0].status).toBe('FORBIDDEN')
  })
  it.each(['LOCAL_BUSINESS_QUERY', 'SPRING_AI_AGENT'])('completes %s with omitted scope and preserves normalized receipts', async mode => {
    const { associationId: _scope, ...wire } = businessFixture()
    const receive = vi.fn()
    const stream = assistantStreamConsumer('test', vi.fn(), vi.fn(), receive)
    await stream.accept({ type: 'start', conversationId: 'test' })
    await stream.accept({ type: 'status', conversationId: 'test', status: { phase: 'GENERATING', mode }, businessResults: [wire] })
    await stream.accept({ type: 'delta', conversationId: 'test', delta: '已查询。' })
    const answer = { conversationId: 'test', answer: '已查询。', mode, modelConnected: mode === 'SPRING_AI_AGENT', traceId: 'test', citations: [], businessResults: [{ ...wire, associationId: null }] }
    expect(await stream.accept({ type: 'complete', conversationId: 'test', answer })).toBe(true)
    expect(receive).toHaveBeenCalledWith([{ ...wire, associationId: null }])
    expect(stream.result().businessResults?.[0].associationId).toBeNull()
  })
  it.each([
    { schemaVersion: 2 }, { id: 'javascript:bad' }, { associationId: '' }, { total: -1 }, { total: 1 },
    { status: 'FORBIDDEN' }, { queriedAt: 'not-a-date' }, { items: [{ ...businessFixture().items[0], fields: { contactPhone: 'secret' } }] },
    { items: Array.from({ length: 11 }, () => businessFixture().items[0]) },
    { scope: 'x'.repeat(401) }, { filters: { constructor: 'bad' } },
  ])('rejects malformed, oversized or sensitive receipt %j', changes => {
    expect(() => parseBusinessResults([{ ...businessFixture(), ...changes }])).toThrow('INVALID_BUSINESS_RESULTS')
  })
  it('rejects duplicate receipts and keeps plain text distinct from structured data', () => {
    expect(() => parseBusinessResults([businessFixture(), businessFixture()])).toThrow()
    expect(() => parseBusinessResults(JSON.stringify([businessFixture()]))).toThrow()
  })
  it('redacts comparisons and preserves missing fields without inventing them', () => {
    const item = memberItem(fixtureMembers[1])
    expect(fieldValue(item, 'services')).toBe('未提供')
    expect(fieldValue(item, 'status')).toBe('已认证')
    expect(JSON.stringify(item)).not.toContain('demo@example.invalid')
    expect(item.fields).not.toHaveProperty('contactPhone')
  })
  it('validates selection sizes separately from paged query totals', () => {
    expect(parseBusinessResults([businessFixture('RECOMMENDATIONS')])).toHaveLength(1)
    expect(parseBusinessResults([businessFixture('COMPARISON')])).toHaveLength(1)
    expect(() => parseBusinessResults([{ ...businessFixture('COMPARISON'), total: 1, items: [businessFixture().items[0]] }])).toThrow()
    expect(() => parseBusinessResults([{ ...businessFixture('RECOMMENDATIONS'), total: 12 }])).toThrow()
  })
  it('retains validated receipts after interrupted text and rejects mutated receipts', async () => {
    const receive = vi.fn()
    const stream = assistantStreamConsumer('test', vi.fn(), vi.fn(), receive)
    await stream.accept({ type: 'start', conversationId: 'test' })
    const event = { type: 'status', conversationId: 'test', status: { phase: 'GENERATING', mode: 'SPRING_AI_AGENT' }, businessResults: [businessFixture()] }
    await stream.accept(event)
    await expect(stream.accept({ type: 'error', conversationId: 'test' })).rejects.toThrow()
    expect(receive).toHaveBeenCalledWith([businessFixture()])
    expect(() => stream.result()).toThrow()
    await expect(stream.accept({ ...event, businessResults: [{ ...businessFixture(), total: 99 }] })).rejects.toMatchObject({ code: 'INVALID_ASSISTANT_STREAM' })
  })
})
