import { describe, expect, it, vi } from 'vitest'
import { assistantStreamConsumer } from './assistant-stream'

const conversationId = 'conversation-test'
const event = (type: string, data: object = {}) => ({ type, conversationId, ...data })
const result = { answer: '已查询到两家企业。', conversationId, mode: 'LOCAL_BUSINESS_QUERY', modelConnected: false, traceId: 'trace', citations: [] }

describe('assistant stream contract', () => {
  it('forwards validated states and completes only with the matching full answer', async () => {
    const delta = vi.fn(), status = vi.fn()
    const stream = assistantStreamConsumer(conversationId, delta, status)
    await stream.accept(event('start', { status: { phase: 'PREPARING', mode: 'AUTO' } }))
    await stream.accept(event('status', { status: { phase: 'LOCAL_RESULT', mode: 'LOCAL_BUSINESS_QUERY' } }))
    await stream.accept(event('delta', { delta: result.answer }))
    expect(await stream.accept(event('complete', { answer: result }))).toBe(true)
    expect(stream.result()).toEqual(result)
    expect(status).toHaveBeenCalledTimes(2)
    expect(delta).toHaveBeenCalledWith(result.answer)
  })

  it.each([null, {}, event('delta', { delta: 'premature' }), event('start', { conversationId: 'other' })])('rejects untrusted or out-of-order event %j', async input => {
    await expect(assistantStreamConsumer(conversationId, vi.fn()).accept(input)).rejects.toMatchObject({ code: 'INVALID_ASSISTANT_STREAM' })
  })

  it('does not treat EOF or empty completion as success', async () => {
    const stream = assistantStreamConsumer(conversationId, vi.fn())
    await stream.accept(event('start'))
    await expect(stream.accept(event('complete', { answer: { ...result, answer: '' } }))).rejects.toMatchObject({ code: 'INVALID_ASSISTANT_STREAM' })
    expect(() => stream.result()).toThrow()
  })

  it.each([{ ...result, answer: 'silently replaced' }, { ...result, conversationId: 'other' }, { ...result, citations: [null] }])('rejects inconsistent terminal answer', async answer => {
    const stream = assistantStreamConsumer(conversationId, vi.fn())
    await stream.accept(event('start'))
    await stream.accept(event('delta', { delta: result.answer }))
    await expect(stream.accept(event('complete', { answer }))).rejects.toMatchObject({ code: 'INVALID_ASSISTANT_STREAM' })
  })

  it('does not expose raw provider errors or oversized output', async () => {
    const stream = assistantStreamConsumer(conversationId, vi.fn())
    await stream.accept(event('start'))
    const error = await stream.accept(event('error', { error: { code: 'ASSISTANT_STREAM_FAILED', message: 'secret-key-do-not-leak' } })).catch(error => error)
    expect(error.message).not.toContain('secret')
    await expect(stream.accept(event('delta', { delta: 'x'.repeat(128001) }))).rejects.toMatchObject({ code: 'INVALID_ASSISTANT_STREAM' })
  })
})
