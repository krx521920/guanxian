// A dependency-free bridge: HTTP must await authentication without importing auth.ts.
let prepare: (() => Promise<void>) | undefined
let unauthorized: ((authorization: string | null) => void) | undefined
let boundary = 0

export function configureSessionGate(beforeRequest: () => Promise<void>, onUnauthorized: (authorization: string | null) => void) {
  prepare = beforeRequest
  unauthorized = onUnauthorized
}

export function changeSessionBoundary() { boundary += 1 }
export function sessionBoundary() { return boundary }

export function prepareSession(signal?: AbortSignal | null): Promise<void> | undefined {
  if (!prepare) return undefined
  const started = boundary
  return new Promise<void>((resolve, reject) => {
    const abort = () => reject(signal?.reason || new DOMException('请求已取消', 'AbortError'))
    if (signal?.aborted) { abort(); return }
    signal?.addEventListener('abort', abort, { once: true })
    // Cancelling one caller must not cancel the renewal shared with other callers.
    prepare!().then(() => {
      if (started !== boundary) throw new Error('账号或管理范围已改变，请确认当前页面后重试。')
      resolve()
    }).catch(reject).finally(() => signal?.removeEventListener('abort', abort))
  })
}

export function reportUnauthorized(authorization: string | null) { unauthorized?.(authorization) }
