import type { AssistantChatAnswer, AssistantStreamStatus } from '../types/domain'
import { ApiRequestError } from './http'
import { parseBusinessResults, selectedEnterpriseIds, type BusinessResult } from './assistant-business-results'

const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const maxAnswerChars = 128000

/** One consumer per request: only an explicit, validated terminal event is a completed answer. */
export function assistantStreamConsumer(
  conversationId: string,
  onDelta: (delta: string) => void | Promise<void>,
  onStatus?: (status: AssistantStreamStatus) => void,
  onBusinessResults?: (results: BusinessResult[]) => void,
  selectedIds: string[] = [],
) {
  const selection = selectedEnterpriseIds(selectedIds)
  let selectionReceiptId: string | null = null
  let selectionDenied = false
  let started = false
  let text = ''
  let answer: AssistantChatAnswer | null = null
  const receipts = new Map<string, string>()
  const invalid = () => new ApiRequestError('回答事件格式无效', `assistant-stream:${conversationId}`, undefined, 'INVALID_ASSISTANT_STREAM')
  const unverified = () => new ApiRequestError('所选企业未得到服务器核验', `assistant-stream:${conversationId}`, undefined, 'UNVERIFIED_ENTERPRISE_SELECTION')

  function status(value: unknown) {
    if (!record(value) || !['PREPARING', 'GENERATING', 'LOCAL_RESULT'].includes(String(value.phase)) || typeof value.mode !== 'string') throw invalid()
    onStatus?.(value as unknown as AssistantStreamStatus)
  }
  function results(value: unknown) {
    let parsed: BusinessResult[]
    try { parsed = parseBusinessResults(value) } catch { throw invalid() }
    for (const receipt of parsed) {
      if (receipt.kind === 'SELECTED_MEMBERS') {
        if (!selection.length || (selectionReceiptId && selectionReceiptId !== receipt.id)
          || (receipt.status === 'OK' && JSON.stringify(receipt.items.map(item => item.id)) !== JSON.stringify(selection))) throw unverified()
        selectionReceiptId = receipt.id
        selectionDenied = receipt.status !== 'OK'
      }
      const previous = receipts.get(receipt.id)
      if (previous && previous !== JSON.stringify(receipt)) throw invalid()
      receipts.set(receipt.id, JSON.stringify(receipt))
    }
    if (receipts.size > 8) throw invalid()
    if (parsed.length) onBusinessResults?.(parsed)
    return parsed
  }

  return {
    async accept(event: unknown): Promise<boolean> {
      if (!record(event) || event.conversationId !== conversationId || answer) throw invalid()
      if (event.type === 'error') {
        // Never expose model/vendor payloads even if an upstream error accidentally contains them.
        const code = record(event.error) && typeof event.error.code === 'string' ? event.error.code : 'ASSISTANT_STREAM_FAILED'
        throw new ApiRequestError('回答未完成', `assistant-stream:${conversationId}`, undefined, code)
      }
      if (event.type === 'start') {
        if (started) throw invalid()
        started = true
        if (event.status != null) status(event.status)
        return false
      }
      if (!started) throw invalid()
      if (event.type === 'status') {
        status(event.status)
      } else if (event.type === 'delta') {
        // Old servers may silently ignore new request fields. Never display ungrounded selected-object text.
        if (selection.length && !selectionReceiptId) throw unverified()
        if (typeof event.delta !== 'string' || text.length + event.delta.length > maxAnswerChars) throw invalid()
        text += event.delta
        await onDelta(event.delta)
      } else if (event.type === 'complete') {
        const result = event.answer
        if (!record(result) || result.conversationId !== conversationId || typeof result.answer !== 'string'
          || !result.answer.trim() || result.answer.length > maxAnswerChars || result.answer.trim() !== text.trim()
          || typeof result.mode !== 'string' || typeof result.modelConnected !== 'boolean'
          || typeof result.traceId !== 'string' || !Array.isArray(result.citations) || result.citations.length > 12
          || !result.citations.every(citation => record(citation) && typeof citation.chunkId === 'string'
            && typeof citation.documentName === 'string' && typeof citation.quote === 'string'
            && (citation.source == null || typeof citation.source === 'string'))) throw invalid()
        const businessResults = results(result.businessResults)
        if (selection.length && (!selectionReceiptId || !businessResults.some(r => r.id === selectionReceiptId)
          || (selectionDenied && result.modelConnected))) throw unverified()
        answer = (result.businessResults == null ? result : { ...result, businessResults }) as unknown as AssistantChatAnswer
        return true
      } else {
        throw invalid()
      }
      if (event.type === 'status') results(event.businessResults)
      return false
    },
    result(): AssistantChatAnswer {
      if (!answer) throw new ApiRequestError('回答传输中断', `assistant-stream:${conversationId}`, undefined, 'INCOMPLETE_ASSISTANT_STREAM')
      return answer
    },
  }
}
