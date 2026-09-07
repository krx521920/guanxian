import type { ModelSettings } from '../../src/services/personal-model'
import { businessFixture, fixtureMembers } from './business-fixtures'
import { selectedEnterpriseIds } from '../../src/services/assistant-business-results'
import { normalizeFitCriteria } from '../../src/services/assistant-fit-check'

// Dev-only synthetic transport. No keys are stored and no business/model request leaves the page.
export const previewRequests: Array<Record<string, unknown>> = []
export const previewMemberReads: string[] = []
export const previewFitRequests: Array<Record<string, unknown>> = []
export const unavailableMembers = new Set<string>()
declare global {
  interface Window { __guanxianPreview: { requests: typeof previewRequests; memberReads: string[]; unavailableMembers: Set<string>; members: typeof fixtureMembers; fitRequests: typeof previewFitRequests; fitDelayMs?: number; fitMalformed?: boolean; modelReadDelayMs?: number; switchRole?: () => void } }
}
export function installPreviewTransport() {
  if (!import.meta.env.DEV || import.meta.env.VITE_AUTH_MODE !== 'demo' || !['127.0.0.1', 'localhost'].includes(location.hostname)) {
    throw new Error('Preview is restricted to local demo development')
  }
  // Observe the installed transport, not a second module instance created by Vite HMR URLs.
  window.__guanxianPreview = { requests: previewRequests, memberReads: previewMemberReads, unavailableMembers, members: fixtureMembers, fitRequests: previewFitRequests }
  const originalFetch = window.fetch.bind(window)
  const settings: ModelSettings = { storageAvailable: true, egressAllowed: true, saved: null, providers: [
    { id: 'DOUBAO', label: '豆包 · 火山方舟', endpoint: 'https://ark.cn-beijing.volces.com/api/v3/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://www.volcengine.com/docs/82379/1330626' },
    { id: 'DEEPSEEK', label: 'DeepSeek', endpoint: 'https://api.deepseek.com/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://api-docs.deepseek.com/' },
    { id: 'KIMI', label: 'Kimi', endpoint: 'https://api.moonshot.cn/v1/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://platform.kimi.com/docs/' },
    { id: 'QWEN', label: '千问 · 阿里云百炼（北京）', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://help.aliyun.com/zh/model-studio/' },
  ] }
    const response = (data: unknown) => Response.json({ code: 'OK', data })
  window.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href)
    if (!url.pathname.startsWith('/api/v1/')) return originalFetch(input, init)
    if (url.origin !== location.origin) throw new Error('External API is disabled in preview')
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {}
    if (url.pathname.endsWith('/model-settings/test')) return response({ success: true, message: '模拟响应成功：未连接真实模型，未验证真实 API Key。', latencyMs: 0 })
    if (url.pathname.endsWith('/model-settings')) {
      if ((!init?.method || init.method === 'GET') && window.__guanxianPreview.modelReadDelayMs) {
        await new Promise(resolve => setTimeout(resolve, window.__guanxianPreview.modelReadDelayMs))
      }
      if (init?.method === 'PUT') {
        if (body.apiKey && body.apiKey !== 'preview-demo-key') return Response.json({ code: 'PREVIEW_ONLY', message: '仅接受演示密钥 preview-demo-key' }, { status: 400 })
        settings.saved = { provider: body.provider, model: body.model, enabled: body.enabled, hasKey: true, revision: crypto.randomUUID() }
      }
      if (init?.method === 'DELETE') { settings.saved = null; return response(true) }
      return response(settings)
    }
    if (url.pathname.endsWith('/assistant/members/fit-check')) {
      previewFitRequests.push(body)
      const ids = selectedEnterpriseIds(body.enterpriseIds), criteria = normalizeFitCriteria(body.criteria)
      const result = businessFixture('MEMBER_FIT_CHECK')
      result.id = crypto.randomUUID(); result.queriedAt = new Date().toISOString()
      result.label = '手动条件核对（本地模拟，未调用模型）'; result.filters = { 条件来源: '用户手动确认' }
      result.items = ids.flatMap(id => result.items.filter(i => i.id === id)); result.total = ids.length
      for (const item of result.items) item.evidence = criteria.map(c => {
        const observed = item.fields[c.field] || ''
        const exact = ['category', 'status'].includes(c.field)
        const supported = !!observed && (exact ? observed.toLowerCase() === c.value.toLowerCase() : !/不|未|没有|暂无|no |not |without /i.test(observed) && observed.toLowerCase().includes(c.value.toLowerCase()))
        return { field: c.field, criterion: c.value, observed, state: supported ? 'MATCHED' : exact && observed ? 'UNMET' : 'INSUFFICIENT', explanation: '本地虚构字段核对；资料不足不等于没有能力，仍需人工核实。' }
      })
      if (result.items.length !== ids.length || ids.some(id => unavailableMembers.has(id))) { result.status = 'FORBIDDEN'; result.total = 0; result.items = [] }
      if (window.__guanxianPreview.fitMalformed) result.items[0].evidence = []
      if (window.__guanxianPreview.fitDelayMs) await new Promise(resolve => setTimeout(resolve, window.__guanxianPreview.fitDelayMs))
      return response({ criteria, result })
    }
    if (/\/members\/[0-9a-f-]+$/.test(url.pathname)) {
      const id = url.pathname.split('/').pop()!
      previewMemberReads.push(id)
      const member = fixtureMembers.find(item => item.id === id)
      if (!member || unavailableMembers.has(id)) return Response.json({ code: 'FORBIDDEN', message: 'fixture unavailable' }, { status: 403 })
      return Response.json({ code: 'OK', data: member }, { headers: { ETag: '"1"' } })
    }
    if (url.pathname.endsWith('/dashboards/association')) return response({ metrics: [], activities: [], sceneDistribution: [], pendingTasks: [] })
    if (url.pathname.endsWith('/system-context/associations') || url.pathname.endsWith('/system-context/enterprises')) return response([])
    if (!url.pathname.endsWith('/assistant/chat/stream')) return response({ items: [], total: 0 })
    previewRequests.push(body)
    const question = String(body.message)
    const brief = body.responseDetail === 'BRIEF'
    const long = question.includes('长回答') || question.includes('停止')
    const fault = question.includes('断流') && previewRequests.filter(item => item.message === question).length === 1
    const selected = selectedEnterpriseIds(body.selectedEnterpriseIds)
    const business = selected.length > 0 || question.includes('企业') || question.includes('推荐')
    const receipt = businessFixture(selected.length ? 'SELECTED_MEMBERS' : question.includes('推荐') ? 'RECOMMENDATIONS' : 'MEMBERS')
    receipt.id = crypto.randomUUID()
    receipt.queriedAt = new Date().toISOString()
    if (selected.length) {
      receipt.label = '所选企业本轮核对'
      receipt.filters = { 选择范围: `用户明确选中的 ${selected.length} 家企业，每轮重新核验（本地模拟）` }
      receipt.items = selected.flatMap(id => receipt.items.filter(item => item.id === id))
      receipt.total = selected.length
      if (selected.some(id => unavailableMembers.has(id)) || receipt.items.length !== selected.length) {
        receipt.status = 'FORBIDDEN'; receipt.items = []; receipt.total = 0
      }
    }
    if (question.includes('失败')) { receipt.status = 'FAILED'; receipt.total = 0; receipt.items = [] }
    if (question.includes('损坏')) receipt.schemaVersion = 99 as 1
    if (question.includes('注入')) receipt.items[0].name = '<img src=x onerror="window.__fixtureXss=true">'
    const businessResults = business && !question.includes('旧版服务') ? [receipt] : []
    const answer = selected.length ? receipt.status === 'OK'
      ? `本地模拟回答：已重新核对所选 ${selected.length} 家虚构企业，未连接真实模型或正式数据库。`
      : '本地模拟：所选资料已不可用或权限发生变化；本轮未调用模型，也未沿用旧企业资料。'
      : brief ? '本地模拟：已展示简洁回答样式，未查询真实业务数据。'
      : '本地模拟回答，未调用真实模型或查询正式数据。\n\n结论：建议按“现状 → 缺口 → 验收”组织客户演示。\n\n1. 现状：展示虚构企业、产品与需求。\n2. 缺口：逐项核对能否完成查询、匹配说明和出处核验。\n3. 验收：记录成功结果、权限边界和未完成项。\n\n'
        + (long ? Array.from({ length: 40 }, (_, i) => `${i + 1}. 演示明细：这只是界面测试文字，不是正式业务结论。\n`).join('') : '这些步骤是建议，不代表系统已经执行。')
    let timer: ReturnType<typeof setTimeout> | undefined
    let ended = false
    const encoder = new TextEncoder()
    let stop = () => {}
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const send = (type: string, data: object = {}) => controller.enqueue(encoder.encode(`data: ${JSON.stringify({ type, conversationId: body.conversationId, ...data })}\n\n`))
        stop = () => { if (ended) return; ended = true; clearTimeout(timer); controller.close() }
        init?.signal?.addEventListener('abort', stop, { once: true })
        send('start', { status: { phase: 'PREPARING', mode: 'AUTO' } })
        let offset = 0
        const tick = () => {
          if (ended) return
          if (offset === 0) send('status', { status: { phase: 'GENERATING', mode: 'PREVIEW' }, businessResults })
          if (fault && offset >= 24) { ended = true; controller.close(); return }
          const delta = answer.slice(offset, offset + 12)
          offset += delta.length
          if (delta) send('delta', { delta })
          if (offset >= answer.length) {
            send('complete', { answer: { answer, conversationId: body.conversationId, mode: 'PREVIEW', modelConnected: false,
              citations: [], traceId: 'local-preview-only', retrievalMode: 'SCOPED_SERVICE', inputTokens: 0, outputTokens: 0, estimatedCost: 0, businessResults } })
            stop()
          } else timer = setTimeout(tick, long ? 30 : 20)
        }
        timer = setTimeout(tick, 100)
      },
      cancel() { ended = true; clearTimeout(timer); init?.signal?.removeEventListener('abort', stop) },
    })
    return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
  }
}
