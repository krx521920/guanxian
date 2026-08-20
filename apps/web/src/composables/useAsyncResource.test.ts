import { effectScope, type EffectScope } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '../services/http'
import { safePageResourceError, useAsyncResource } from './useAsyncResource'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const scopes: EffectScope[] = []

function createResource<T>(loader: () => Promise<T>) {
  const scope = effectScope()
  scopes.push(scope)
  const resource = scope.run(() => useAsyncResource(loader))
  if (!resource) throw new Error('resource scope was not created')
  return { resource, scope }
}

describe('useAsyncResource', () => {
  afterEach(() => {
    scopes.splice(0).forEach((scope) => scope.stop())
    vi.restoreAllMocks()
  })

  it('loads data and clears the loading state', async () => {
    const loader = vi.fn().mockResolvedValue({ total: 106 })
    const { resource } = createResource(loader)

    expect(resource.loading.value).toBe(false)
    const pending = resource.load()
    expect(resource.loading.value).toBe(true)
    expect(resource.data.value).toBeNull()

    await pending

    expect(loader).toHaveBeenCalledOnce()
    expect(resource.data.value).toEqual({ total: 106 })
    expect(resource.error.value).toBeNull()
    expect(resource.loading.value).toBe(false)
  })

  it.each([
    [new ApiRequestError('secret timeout body', 'request-timeout', undefined, 'REQUEST_TIMEOUT'), '请求超时，请稍后重试。'],
    [new ApiRequestError('secret auth body', 'request-auth', 401, 'AUTH_REQUIRED'), '登录状态已失效，请重新登录。'],
    [new ApiRequestError('secret forbidden body', 'request-forbidden', 403, 'FORBIDDEN'), '暂无权限访问该内容。'],
    [new ApiRequestError('secret server body', 'request-server', 503, 'UNAVAILABLE'), '服务暂时不可用，请稍后重试。'],
    [new ApiRequestError('secret validation body', 'request-validation', 422, 'VALIDATION'), '数据加载失败，请稍后重试。'],
  ])('maps an API failure to safe page state', async (failure, expectedMessage) => {
    const { resource } = createResource(() => Promise.reject(failure))

    await resource.load()

    expect(resource.error.value).toEqual({ message: expectedMessage, requestId: failure.requestId })
    expect(JSON.stringify(resource.error.value)).not.toContain('secret')
    expect(resource.loading.value).toBe(false)
  })

  it('hides an unsafe request ID and all details from an ordinary error', () => {
    const apiError = new ApiRequestError('response body secret', 'unsafe request id', 500, 'FAILED')
    expect(safePageResourceError(apiError)).toEqual({
      message: '服务暂时不可用，请稍后重试。',
      requestId: undefined,
    })

    const ordinaryError = new Error('database password in body')
    ordinaryError.stack = 'private stack trace'
    const safeOrdinaryError = safePageResourceError(ordinaryError)
    expect(safeOrdinaryError).toEqual({ message: '数据加载失败，请稍后重试。' })
    expect(safeOrdinaryError).not.toHaveProperty('requestId')
  })

  it('captures a synchronous loader exception without rejecting load', async () => {
    const { resource } = createResource(() => {
      throw new Error('private synchronous failure')
    })

    await expect(resource.load()).resolves.toBeUndefined()
    expect(resource.error.value).toEqual({ message: '数据加载失败，请稍后重试。' })
  })

  it('retries after a failure and replaces the error with data', async () => {
    const loader = vi.fn()
      .mockRejectedValueOnce(new ApiRequestError('private', 'retry-request-1', 502, 'UPSTREAM'))
      .mockResolvedValueOnce(['recovered'])
    const { resource } = createResource(loader)

    await resource.load()
    expect(resource.error.value?.requestId).toBe('retry-request-1')

    const retry = resource.load()
    expect(resource.loading.value).toBe(true)
    expect(resource.error.value).toBeNull()
    await retry

    expect(loader).toHaveBeenCalledTimes(2)
    expect(resource.data.value).toEqual(['recovered'])
    expect(resource.error.value).toBeNull()
  })

  it('deduplicates concurrent load and retry clicks', async () => {
    const pendingLoader = deferred<string>()
    const loader = vi.fn(() => pendingLoader.promise)
    const { resource } = createResource(loader)

    const first = resource.load()
    const second = resource.load()

    expect(first).toBe(second)
    await Promise.resolve()
    expect(loader).toHaveBeenCalledOnce()

    pendingLoader.resolve('latest')
    await Promise.all([first, second])
    expect(resource.data.value).toBe('latest')
  })

  it('does not update state after its component scope is disposed', async () => {
    const pendingLoader = deferred<string>()
    const { resource, scope } = createResource(() => pendingLoader.promise)
    const pending = resource.load()
    scope.stop()

    pendingLoader.resolve('late result')
    await pending

    expect(resource.data.value).toBeNull()
    expect(resource.error.value).toBeNull()
    expect(resource.loading.value).toBe(true)
  })

  it('ignores loads requested after disposal', async () => {
    const loader = vi.fn().mockResolvedValue('must not load')
    const { resource, scope } = createResource(loader)
    scope.stop()

    await expect(resource.load()).resolves.toBeUndefined()
    expect(loader).not.toHaveBeenCalled()
    expect(resource.data.value).toBeNull()
  })

  it('does not publish a late rejection after disposal', async () => {
    const pendingLoader = deferred<string>()
    const { resource, scope } = createResource(() => pendingLoader.promise)
    const pending = resource.load()
    scope.stop()

    pendingLoader.reject(new ApiRequestError('private late body', 'late-request', 500, 'FAILED'))
    await pending

    expect(resource.error.value).toBeNull()
  })
})
