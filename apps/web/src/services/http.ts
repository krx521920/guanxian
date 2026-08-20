interface ApiEnvelopeCandidate<T> {
  code: unknown
  message?: string
  data?: T
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
// Mock data is both opt-in and development-only so production failures are never hidden.
const allowMockFallback = import.meta.env.MODE !== 'production' && import.meta.env.VITE_MOCK_FALLBACK === 'true'

const successCodes = new Set<string | number>(['OK', 'SUCCESS', 0, 200])
const requestIdHeader = 'X-Request-Id'
const requestIdPattern = /^[A-Za-z0-9._:-]{1,128}$/

export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly requestId: string,
    public readonly status?: number,
    public readonly code?: string | number,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

function isSafeRequestId(value: string | null): value is string {
  return value !== null && requestIdPattern.test(value)
}

function generateRequestId(): string {
  if (typeof globalThis.crypto.randomUUID === 'function') return globalThis.crypto.randomUUID()

  const randomBytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(randomBytes)
  const randomHex = Array.from(randomBytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `web-${randomHex}`
}

function headerEntries(headersInit?: HeadersInit): Array<[string, string]> {
  if (!headersInit) return []
  if (headersInit instanceof Headers) return [...headersInit.entries()]
  if (Array.isArray(headersInit)) return headersInit.map(([name, value]) => [name, value])
  return Object.entries(headersInit)
}

function prepareHeaders(headersInit?: HeadersInit): { headers: Headers; requestId: string } {
  const entries = headerEntries(headersInit)
  const requestIdValues = entries
    .filter(([name]) => name.toLowerCase() === requestIdHeader.toLowerCase())
    .map(([, value]) => value)
  const suppliedRequestId = requestIdValues.length === 1 ? requestIdValues[0] : null
  const requestId = isSafeRequestId(suppliedRequestId) ? suppliedRequestId : generateRequestId()
  const headers = new Headers()

  entries.forEach(([name, value]) => {
    if (name.toLowerCase() !== requestIdHeader.toLowerCase()) headers.append(name, value)
  })
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  headers.set(requestIdHeader, requestId)

  return { headers, requestId }
}

function responseRequestId(response: Response, outboundRequestId: string): string {
  const responseHeader = response.headers.get(requestIdHeader)
  return isSafeRequestId(responseHeader) ? responseHeader : outboundRequestId
}

function isEnvelopeCandidate<T>(payload: unknown): payload is ApiEnvelopeCandidate<T> {
  return Boolean(
    payload
      && typeof payload === 'object'
      && 'code' in payload
      && (
        'data' in payload
        || 'message' in payload
        || successCodes.has(payload.code as string | number)
      ),
  )
}

function errorFromResponse(
  response: Response,
  outboundRequestId: string,
  payload?: ApiEnvelopeCandidate<unknown>,
): ApiRequestError {
  return new ApiRequestError(
    payload?.message || `HTTP ${response.status}`,
    responseRequestId(response, outboundRequestId),
    response.status,
    typeof payload?.code === 'string' || typeof payload?.code === 'number' ? payload.code : undefined,
  )
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
  mock?: () => Promise<T> | T,
): Promise<T> {
  const { headers, requestId } = prepareHeaders(options.headers)
  const controller = new AbortController()
  const externalSignal = options.signal
  let abortSource: 'external' | 'timeout' | undefined

  const externalAbortError = () => externalSignal!.reason instanceof Error
    ? externalSignal!.reason
    : new DOMException('请求已取消', 'AbortError')

  const abortFromExternal = () => {
    if (controller.signal.aborted) return
    abortSource = 'external'
    controller.abort(externalSignal!.reason)
  }
  if (externalSignal?.aborted) abortFromExternal()
  else externalSignal?.addEventListener('abort', abortFromExternal, { once: true })

  const timeout = window.setTimeout(() => {
    if (controller.signal.aborted) return
    abortSource = 'timeout'
    controller.abort()
  }, 2500)

  try {
    if (abortSource === 'external') throw externalAbortError()

    let response: Response
    try {
      response = await fetch(`${baseUrl}${path.startsWith('/') ? path : `/${path}`}`, {
        ...options,
        headers,
        signal: controller.signal,
      })
    } catch (error) {
      if (abortSource === 'timeout') {
        throw new ApiRequestError('请求超时', requestId, undefined, 'REQUEST_TIMEOUT')
      }
      if (abortSource === 'external') {
        throw externalAbortError()
      }
      if (allowMockFallback && mock && error instanceof TypeError) return mock()
      throw error
    }

    let payload: ApiEnvelopeCandidate<T> | T | undefined
    try {
      payload = (await response.json()) as ApiEnvelopeCandidate<T> | T
    } catch {
      if (!response.ok) throw errorFromResponse(response, requestId)
      throw new ApiRequestError('接口返回格式无效', responseRequestId(response, requestId), response.status)
    }

    if (!response.ok) {
      throw errorFromResponse(response, requestId, isEnvelopeCandidate(payload) ? payload : undefined)
    }

    if (isEnvelopeCandidate<T>(payload)) {
      const envelope = payload
      if (typeof envelope.code !== 'string' && typeof envelope.code !== 'number') {
        throw new ApiRequestError('接口返回格式无效', responseRequestId(response, requestId), response.status)
      }
      if (!successCodes.has(envelope.code)) {
        throw new ApiRequestError(
          envelope.message || '接口请求失败',
          responseRequestId(response, requestId),
          response.status,
          envelope.code,
        )
      }
      if (envelope.data === undefined) {
        throw new ApiRequestError(
          envelope.message || '接口未返回数据',
          responseRequestId(response, requestId),
          response.status,
          envelope.code,
        )
      }
      return envelope.data
    }
    return payload as T
  } finally {
    window.clearTimeout(timeout)
    externalSignal?.removeEventListener('abort', abortFromExternal)
  }
}
