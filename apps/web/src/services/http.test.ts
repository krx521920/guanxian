import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const browserWindow = {
  setTimeout: globalThis.setTimeout.bind(globalThis),
  clearTimeout: globalThis.clearTimeout.bind(globalThis),
}
const randomUUIDMock = vi.fn(() => 'generated-request-id')

async function loadRequest(options?: { mockFallback?: boolean; baseUrl?: string; mode?: string }) {
  vi.resetModules()
  vi.stubEnv('VITE_MOCK_FALLBACK', options?.mockFallback ? 'true' : 'false')
  vi.stubEnv('MODE', options?.mode ?? 'test')
  if (options?.baseUrl !== undefined) vi.stubEnv('VITE_API_BASE_URL', options.baseUrl)
  return import('./http')
}

describe('request', () => {
  beforeEach(() => {
    vi.stubGlobal('window', browserWindow)
    randomUUIDMock.mockReset().mockReturnValue('generated-request-id')
    vi.stubGlobal('crypto', { randomUUID: randomUUIDMock })
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('unwraps a successful API envelope and normalizes the request URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'OK', message: 'success', data: { total: 106 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest({ baseUrl: '/api/v1/' })

    await expect(request<{ total: number }>('members')).resolves.toEqual({ total: 106 })
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/members')
    const headers = new Headers(init.headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('X-Request-Id')).toBe('generated-request-id')
    expect([...headers.keys()]).toEqual(['content-type', 'x-request-id'])
    expect(randomUUIDMock).toHaveBeenCalledOnce()
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('accepts direct JSON responses and preserves caller headers including a safe request ID', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ enabled: true }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await expect(
      request('/feature', {
        headers: {
          Authorization: 'Bearer test',
          'Content-Type': 'application/merge-patch+json',
          'x-request-id': 'client.Trace:01',
        },
      }),
    ).resolves.toEqual({ enabled: true })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer test')
    expect(headers.get('Content-Type')).toBe('application/merge-patch+json')
    expect(headers.get('X-Request-Id')).toBe('client.Trace:01')
    expect(randomUUIDMock).not.toHaveBeenCalled()
  })

  it('preserves a safe request ID supplied through a Headers instance', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()
    const callerHeaders = new Headers({ Authorization: 'Bearer headers', 'X-Request-Id': 'headers.instance-1' })

    await request('/health', { headers: callerHeaders })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer headers')
    expect(headers.get('X-Request-Id')).toBe('headers.instance-1')
    expect(randomUUIDMock).not.toHaveBeenCalled()
  })

  it('preserves a safe request ID supplied through header tuples', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await request('/health', {
      headers: [['Authorization', 'Bearer tuples'], ['X-Request-Id', 'tuple:id-1']],
    })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer tuples')
    expect(headers.get('X-Request-Id')).toBe('tuple:id-1')
    expect(randomUUIDMock).not.toHaveBeenCalled()
  })

  it.each([
    '',
    'contains space',
    'bad/request/id',
    'a'.repeat(129),
    'safe\r\nInjected: value',
  ])('replaces an unsafe caller request ID %j', async (unsafeRequestId) => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await expect(request('/health', { headers: { 'X-Request-Id': unsafeRequestId } })).resolves.toBe('done')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('X-Request-Id')).toBe('generated-request-id')
  })

  it('replaces ambiguous duplicate request ID headers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await request('/health', {
      headers: [['X-Request-Id', 'client-one'], ['x-request-id', 'client-two']],
    })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('X-Request-Id')).toBe('generated-request-id')
  })

  it('uses the default base URL and keeps an already absolute API path intact', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(null))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await expect(request('/health')).resolves.toBeNull()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/health')
  })

  it('uses crypto.getRandomValues when randomUUID is unavailable', async () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.forEach((_value, index) => { bytes[index] = index })
      return bytes
    })
    vi.stubGlobal('crypto', { getRandomValues })
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' }))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await expect(request('/health')).resolves.toBe('done')
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('X-Request-Id')).toBe('web-000102030405060708090a0b0c0d0e0f')
    expect(getRandomValues).toHaveBeenCalledOnce()
  })

  it.each(['SUCCESS', 0, 200])('accepts the supported success code %s', async (code) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code, data: 'ok' })))
    const { request } = await loadRequest()

    await expect(request('/health')).resolves.toBe('ok')
  })

  it.each([
    { code: 'VALVE-01', name: '球阀' },
    { data: ['technical-data'], name: '球阀' },
  ])('does not unwrap a direct object that is not a complete envelope: %j', async (directPayload) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(directPayload)))
    const { request } = await loadRequest()

    await expect(request('/products')).resolves.toEqual(directPayload)
  })

  it('rejects a successful HTTP response carrying a failed business code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json(
          { code: 'VALIDATION_FAILED', message: '企业名称不能为空', data: null },
          { headers: { 'X-Request-Id': 'server-business-id' } },
        ),
      ),
    )
    const { ApiRequestError, request } = await loadRequest()

    const error = await request('/members').catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(ApiRequestError)
    expect(error).toMatchObject({
      name: 'ApiRequestError',
      message: '企业名称不能为空',
      requestId: 'server-business-id',
      status: 200,
      code: 'VALIDATION_FAILED',
    })
  })

  it('recognizes a failed envelope with a message even when data is omitted', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code: 'BUSINESS_FAILED', message: '处理失败' })))
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toMatchObject({
      message: '处理失败',
      code: 'BUSINESS_FAILED',
    })
  })

  it.each([null, true, undefined])('rejects an envelope with invalid code %j', async (code) => {
    const response = {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: vi.fn().mockResolvedValue({ code, data: 'must-not-be-used' }),
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toMatchObject({
      message: '接口返回格式无效',
      status: 200,
    })
  })

  it('surfaces the backend message, status and code for HTTP errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json(
          {
            code: 'AUTHENTICATION_REQUIRED',
            message: '请先登录',
            data: null,
            debug: { secret: 'must-not-leak' },
          },
          { status: 401, headers: { 'X-Request-Id': 'server-auth-id' } },
        ),
      ),
    )
    const { ApiRequestError, request } = await loadRequest()

    const error = await request('/members').catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(ApiRequestError)
    expect(error).toMatchObject({
      message: '请先登录',
      requestId: 'server-auth-id',
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
    })
    expect(error).not.toHaveProperty('data')
    expect(error).not.toHaveProperty('body')
    expect(JSON.stringify(error)).not.toContain('must-not-leak')
  })

  it('preserves a numeric backend code on HTTP errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ code: 42901, message: '请求过于频繁', data: null }, { status: 429 })),
    )
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toMatchObject({
      message: '请求过于频繁',
      requestId: 'generated-request-id',
      status: 429,
      code: 42901,
    })
  })

  it('falls back to the outbound ID when the response request ID is unsafe', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        Response.json(
          { code: 'FORBIDDEN', message: '无权访问', data: null },
          { status: 403, headers: { 'X-Request-Id': 'unsafe server id' } },
        ),
      ),
    )
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toMatchObject({ requestId: 'generated-request-id' })
  })

  it('never hides HTTP or business contract errors behind mock data', async () => {
    const mock = vi.fn().mockReturnValue(['mock'])
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(Response.json({ code: 'FORBIDDEN', message: '无权访问', data: null }, { status: 403 })),
    )
    const { request } = await loadRequest({ mockFallback: true })

    await expect(request('/members', {}, mock)).rejects.toMatchObject({ status: 403, code: 'FORBIDDEN' })
    expect(mock).not.toHaveBeenCalled()
  })

  it('does not treat a primitive JSON error body as successful data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json('unauthorized', { status: 401 })))
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toEqual(
      expect.objectContaining({ message: 'HTTP 401', status: 401 }),
    )
  })

  it('uses the HTTP status when an error response is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('gateway down', { status: 502 })))
    const { ApiRequestError, request } = await loadRequest()

    await expect(request('/members')).rejects.toEqual(
      expect.objectContaining({ message: 'HTTP 502', status: 502 }),
    )
  })

  it('rejects malformed JSON returned with a successful status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not-json', { status: 200 })))
    const { ApiRequestError, request } = await loadRequest()

    await expect(request('/members')).rejects.toEqual(
      expect.objectContaining({ message: '接口返回格式无效', status: 200 }),
    )
  })

  it('rejects envelopes that omit data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code: 'OK', message: '缺少响应数据' })))
    const { request } = await loadRequest()

    await expect(request('/members')).rejects.toThrow('缺少响应数据')
  })

  it('uses stable fallback messages when the backend omits message fields', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code: 'BUSINESS_FAILED', data: null })))
    const { request } = await loadRequest()
    await expect(request('/members')).rejects.toThrow('接口请求失败')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code: 'OK' })))
    await expect(request('/members')).rejects.toThrow('接口未返回数据')
  })

  it('aborts timed-out requests and always clears the timeout handle', async () => {
    let timeoutCallback: (() => void) | undefined
    const setTimeoutMock = vi.fn((callback: () => void) => {
      timeoutCallback = callback
      return 42
    })
    const clearTimeoutMock = vi.fn()
    vi.stubGlobal('window', { setTimeout: setTimeoutMock, clearTimeout: clearTimeoutMock })
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })),
    )
    const { request } = await loadRequest()

    const pendingRequest = request('/slow')
    expect(setTimeoutMock).toHaveBeenCalledWith(expect.any(Function), 2500)
    timeoutCallback?.()

    await expect(pendingRequest).rejects.toMatchObject({
      name: 'ApiRequestError',
      message: '请求超时',
      requestId: 'generated-request-id',
      code: 'REQUEST_TIMEOUT',
    })
    expect(clearTimeoutMock).toHaveBeenCalledWith(42)
  })

  it('preserves caller cancellation and never falls back to mock data', async () => {
    const externalController = new AbortController()
    const cancellation = new DOMException('用户取消', 'AbortError')
    const mock = vi.fn().mockReturnValue(['mock'])
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))
    }))
    vi.stubGlobal(
      'fetch',
      fetchMock,
    )
    const { request } = await loadRequest({ mockFallback: true })

    const pendingRequest = request('/members', {
      signal: externalController.signal,
      headers: { 'X-Request-Id': 'cancel-request-id' },
    }, mock)
    externalController.abort(cancellation)

    await expect(pendingRequest).rejects.toBe(cancellation)
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('X-Request-Id')).toBe('cancel-request-id')
    expect(mock).not.toHaveBeenCalled()
  })

  it('normalizes a non-Error caller cancellation reason', async () => {
    const externalController = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      })),
    )
    const { request } = await loadRequest()

    const pendingRequest = request('/members', { signal: externalController.signal })
    externalController.abort('plain cancellation reason')

    await expect(pendingRequest).rejects.toMatchObject({
      name: 'AbortError',
      message: '请求已取消',
    })
  })

  it('keeps timeout as the first abort cause when caller cancellation races afterward', async () => {
    let timeoutCallback: (() => void) | undefined
    vi.stubGlobal('window', {
      setTimeout: (callback: () => void) => {
        timeoutCallback = callback
        return 43
      },
      clearTimeout: vi.fn(),
    })
    const externalController = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      })),
    )
    const { request } = await loadRequest()

    const pendingRequest = request('/members', { signal: externalController.signal })
    timeoutCallback?.()
    externalController.abort(new DOMException('late cancellation', 'AbortError'))

    await expect(pendingRequest).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' })
  })

  it('keeps caller cancellation as the first abort cause when timeout races afterward', async () => {
    let timeoutCallback: (() => void) | undefined
    vi.stubGlobal('window', {
      setTimeout: (callback: () => void) => {
        timeoutCallback = callback
        return 44
      },
      clearTimeout: vi.fn(),
    })
    const externalController = new AbortController()
    const cancellation = new DOMException('caller won', 'AbortError')
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      })),
    )
    const { request } = await loadRequest()

    const pendingRequest = request('/members', { signal: externalController.signal })
    externalController.abort(cancellation)
    timeoutCallback?.()

    await expect(pendingRequest).rejects.toBe(cancellation)
  })

  it('rejects a pre-cancelled request without calling fetch', async () => {
    const externalController = new AbortController()
    const cancellation = new DOMException('请求发起前已取消', 'AbortError')
    externalController.abort(cancellation)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()

    await expect(request('/members', { signal: externalController.signal })).rejects.toBe(cancellation)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('cleans the caller abort listener after a successful request', async () => {
    const externalController = new AbortController()
    const addListener = vi.spyOn(externalController.signal, 'addEventListener')
    const removeListener = vi.spyOn(externalController.signal, 'removeEventListener')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ code: 'OK', data: 'done' })))
    const { request } = await loadRequest()

    await expect(request('/health', { signal: externalController.signal })).resolves.toBe('done')
    expect(addListener).toHaveBeenCalledWith('abort', expect.any(Function), { once: true })
    expect(removeListener).toHaveBeenCalledWith('abort', expect.any(Function))
  })

  it('keeps concurrent requests isolated when one caller cancels', async () => {
    const firstController = new AbortController()
    const cancellation = new DOMException('只取消第一个请求', 'AbortError')
    randomUUIDMock
      .mockReturnValueOnce('concurrent-request-1')
      .mockReturnValueOnce('concurrent-request-2')
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/second')) return Promise.resolve(Response.json({ code: 'OK', data: 'second' }))
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(init.signal?.reason))
      })
    })
    vi.stubGlobal(
      'fetch',
      fetchMock,
    )
    const { request } = await loadRequest()

    const first = request('/first', { signal: firstController.signal })
    const second = request('/second')
    firstController.abort(cancellation)

    await expect(first).rejects.toBe(cancellation)
    await expect(second).resolves.toBe('second')
    const firstHeaders = new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers)
    const secondHeaders = new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers)
    expect(firstHeaders.get('X-Request-Id')).toBe('concurrent-request-1')
    expect(secondHeaders.get('X-Request-Id')).toBe('concurrent-request-2')
  })

  it('uses mock data after a network failure only when explicitly enabled', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)
    const mock = vi.fn().mockReturnValue([{ id: 'mock-member' }])
    const { request } = await loadRequest({ mockFallback: true })

    await expect(request('/members', {}, mock)).resolves.toEqual([{ id: 'mock-member' }])
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('X-Request-Id')).toBe('generated-request-id')
    expect(mock).toHaveBeenCalledOnce()
  })

  it('disables mock fallback unconditionally in production mode', async () => {
    const networkError = new TypeError('Failed to fetch')
    const mock = vi.fn().mockReturnValue([])
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(networkError))
    const { request } = await loadRequest({ mockFallback: true, mode: 'production' })

    await expect(request('/members', {}, mock)).rejects.toBe(networkError)
    expect(mock).not.toHaveBeenCalled()
  })

  it('does not classify arbitrary fetch exceptions as recoverable network failures', async () => {
    const unexpectedError = new Error('fetch implementation failed')
    const mock = vi.fn().mockReturnValue([])
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(unexpectedError))
    const { request } = await loadRequest({ mockFallback: true })

    await expect(request('/members', {}, mock)).rejects.toBe(unexpectedError)
    expect(mock).not.toHaveBeenCalled()
  })

  it('does not hide API failures when mock fallback is disabled', async () => {
    const networkError = new TypeError('Failed to fetch')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(networkError))
    const mock = vi.fn().mockReturnValue([])
    const { request } = await loadRequest({ mockFallback: false })

    await expect(request('/members', {}, mock)).rejects.toBe(networkError)
    expect(mock).not.toHaveBeenCalled()
  })

  it('does not invent mock data when no mock provider is supplied', async () => {
    const networkError = new TypeError('Failed to fetch')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(networkError))
    const { request } = await loadRequest({ mockFallback: true })

    await expect(request('/members')).rejects.toBe(networkError)
  })
  it('adds the in-memory OIDC access token without overriding caller authorization', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(Response.json({ code: 'OK', data: 'done' })))
    vi.stubGlobal('fetch', fetchMock)
    const { request } = await loadRequest()
    const { setAccessToken } = await import('./token-store')
    setAccessToken('verified-access-token')

    await request('/members')
    let headers = new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer verified-access-token')

    await request('/members', { headers: { Authorization: 'Bearer caller-token' } })
    headers = new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers)
    expect(headers.get('Authorization')).toBe('Bearer caller-token')
  })

})
