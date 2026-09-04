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
  if (error?.code === 'RAG_LIMIT_EXCEEDED') return '问题或检索结果过长，请缩短问题后再试。'
  if (error?.code === 'REQUEST_TIMEOUT') return '回答超时，请稍后重试。'
  return '智能助手暂时无法回答，请稍后重试。'
}
