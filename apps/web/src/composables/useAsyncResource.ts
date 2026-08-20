import { onScopeDispose, readonly, ref, shallowRef } from 'vue'
import { ApiRequestError } from '../services/http'

const safeRequestIdPattern = /^[A-Za-z0-9._:-]{1,128}$/

export interface PageResourceError {
  message: string
  requestId?: string
}

export function safePageResourceError(reason: unknown): PageResourceError {
  if (!(reason instanceof ApiRequestError)) {
    return { message: '数据加载失败，请稍后重试。' }
  }

  let message = '数据加载失败，请稍后重试。'
  if (reason.code === 'REQUEST_TIMEOUT') message = '请求超时，请稍后重试。'
  else if (reason.status === 401) message = '登录状态已失效，请重新登录。'
  else if (reason.status === 403) message = '暂无权限访问该内容。'
  else if (reason.status! >= 500) message = '服务暂时不可用，请稍后重试。'

  return {
    message,
    requestId: safeRequestIdPattern.test(reason.requestId) ? reason.requestId : undefined,
  }
}

export function useAsyncResource<T>(loader: () => Promise<T>) {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref<PageResourceError | null>(null)
  let disposed = false
  let inFlight: Promise<void> | null = null

  function load(): Promise<void> {
    if (disposed) return Promise.resolve()
    if (inFlight) return inFlight

    loading.value = true
    error.value = null

    const task = Promise.resolve()
      .then(loader)
      .then((result) => {
        if (!disposed) data.value = result
      })
      .catch((reason: unknown) => {
        if (!disposed) error.value = safePageResourceError(reason)
      })
      .finally(() => {
        if (!disposed) loading.value = false
        inFlight = null
      })

    inFlight = task
    return task
  }

  onScopeDispose(() => {
    disposed = true
  })

  return {
    data: readonly(data),
    loading: readonly(loading),
    error: readonly(error),
    load,
  }
}
