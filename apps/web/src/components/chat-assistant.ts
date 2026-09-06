export interface AssistantErrorLike {
  code?: unknown
  status?: unknown
}

export function safeCitationUrl(value: string | null | undefined): string | null {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : null
  } catch {
    return null
  }
}

export function assistantErrorMessage(reason: unknown): string {
  const error = reason && typeof reason === 'object' ? reason as AssistantErrorLike : null
  if (error?.status === 401) return '登录状态已失效，请重新登录后再试。'
  if (error?.status === 403) return '当前身份没有资料问答权限，或尚未选择管理协会。'
  if (error?.code === 'UNSAFE_KNOWLEDGE_INPUT') return '这个问题包含系统无法安全处理的内容，请换一种简洁问法。'
  if (error?.code === 'RAG_LIMIT_EXCEEDED') return '本轮已达到内容或查询次数限制，请缩短问题、缩小范围或分步提问。'
  if (error?.code === 'REQUEST_TIMEOUT') return '回答超时，请稍后重试。'
  if (error?.code === 'INCOMPLETE_ASSISTANT_STREAM') return '连接中断，回答尚未完成。可以重新回答。'
  if (error?.code === 'UNVERIFIED_ENTERPRISE_SELECTION') return '服务端未核验所选企业，已停止显示回答。请检查前后端版本，或清除选择后重新查询。'
  if (error?.code === 'INVALID_ASSISTANT_STREAM' || error?.code === 'INVALID_EVENT_STREAM') return '回答传输异常，未将这段内容标记为完成。请重试。'
  if (error?.code === 'EVENT_STREAM_LIMIT') return '回答内容过长，请缩小问题范围后重试。'
  if (error?.status === 429) return '请求过于频繁，请稍后再试。'
  return '智能助手暂时无法回答，请稍后重试。'
}

export function assistantPhaseLabel(phase: string): string {
  if (phase === 'PREPARING') return '正在准备当前权限范围内的数据'
  if (phase === 'GENERATING') return '模型正在处理，可按需调用只读工具'
  if (phase === 'LOCAL_RESULT') return '正在返回本地查询结果'
  return '请求已发送，等待服务响应'
}

export function shouldSendAssistantMessage(event: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'isComposing' | 'keyCode'>): boolean {
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229
}

export function assistantModeLabel(mode: string | null | undefined): string {
  if (mode === 'LOCAL_BUSINESS_QUERY') return '业务查询模式'
  if (mode === 'SPRING_AI_AGENT') return 'AI 综合模式'
  if (mode === 'AUTO') return '自动选择模式'
  if (mode === 'PREVIEW') return '本地模拟模式'
  if (mode === 'RETRIEVAL_SUMMARY' || mode === 'NO_EVIDENCE' || mode === 'EXTERNAL_MODEL') return '知识库模式'
  return '模式待确认'
}
