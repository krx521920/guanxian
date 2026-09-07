import { afterEach, describe, expect, it, vi } from 'vitest'

const ROLE_STORAGE_KEY = 'guanxian.demo.role'

type StoredValues = Record<string, string>

function createStorage(initial: StoredValues = {}): Storage {
  const entries = new Map(Object.entries(initial))
  return {
    get length() {
      return entries.size
    },
    clear: () => entries.clear(),
    getItem: (key) => entries.get(key) ?? null,
    key: (index) => [...entries.keys()][index] ?? null,
    removeItem: (key) => entries.delete(key),
    setItem: (key, value) => entries.set(key, value),
  }
}

interface Scenario {
  path?: string
  search?: string
  authority?: string
  clientId?: string
  configMode?: string
  user?: Record<string, unknown> | null
  callbackUser?: Record<string, unknown> | null
  getUserError?: Error
  callbackError?: Error
  currentUser?: Record<string, unknown>
  scopedCurrentUser?: Record<string, unknown>
  systemContext?: { associationId: string | null; enterpriseId: string | null }
  session?: Storage
}

async function loadOidc(scenario: Scenario = {}) {
  vi.resetModules()
  vi.stubEnv('MODE', 'test')
  vi.stubEnv('VITE_AUTH_MODE', scenario.configMode ?? 'oidc')
  vi.stubEnv('VITE_OIDC_AUTHORITY', scenario.authority ?? ' https://identity.example.test/realm ')
  vi.stubEnv('VITE_OIDC_CLIENT_ID', scenario.clientId ?? ' guanxian-web ')
  vi.stubEnv('VITE_OIDC_REDIRECT_URI', '')
  vi.stubEnv('VITE_OIDC_POST_LOGOUT_REDIRECT_URI', '')
  vi.stubEnv('VITE_OIDC_SCOPE', '')

  const session = scenario.session ?? createStorage({ [ROLE_STORAGE_KEY]: 'SYSTEM_ADMIN' })
  vi.stubGlobal('sessionStorage', session)
  vi.stubGlobal('localStorage', createStorage())
  const events = new EventTarget()
  const documentEvents = new EventTarget()
  vi.stubGlobal('document', { createElement: vi.fn(() => ({})), visibilityState: 'visible', addEventListener: documentEvents.addEventListener.bind(documentEvents) })
  vi.stubGlobal('window', {
    addEventListener: events.addEventListener.bind(events),
    location: {
      origin: 'https://app.example.test',
      pathname: scenario.path ?? '/',
      search: scenario.search ?? '',
    },
  })

  let managerSettings: Record<string, unknown> | null = null
  let expiredHandler: (() => void) | null = null
  let expiringHandler: (() => void) | null = null
  const getUser = scenario.getUserError
    ? vi.fn().mockRejectedValue(scenario.getUserError)
    : vi.fn().mockResolvedValue(scenario.user ?? null)
  const signinRedirectCallback = scenario.callbackError
    ? vi.fn().mockRejectedValue(scenario.callbackError)
    : vi.fn().mockResolvedValue(scenario.callbackUser ?? null)
  const signinRedirect = vi.fn().mockResolvedValue(undefined)
  const signoutRedirect = vi.fn().mockResolvedValue(undefined)
  const removeUser = vi.fn().mockImplementation(async () => { getUser.mockResolvedValue(null) })
  const signinSilent = vi.fn().mockImplementation(async () => {
    const refreshed = { ...scenario.user, access_token: 'refreshed-token', refresh_token: 'rotated-refresh', expired: false, expires_in: 300 }
    getUser.mockResolvedValue(refreshed)
    return refreshed
  })

  class MockWebStorageStateStore {
    constructor(readonly settings: Record<string, unknown>) {}
  }

  class MockUserManager {
    readonly events = {
      addAccessTokenExpiring: vi.fn((handler: () => void) => { expiringHandler = handler }),
      addAccessTokenExpired: vi.fn((handler: () => void) => {
        expiredHandler = handler
      }),
    }

    constructor(settings: Record<string, unknown>) {
      managerSettings = settings
    }

    getUser = getUser
    signinRedirectCallback = signinRedirectCallback
    signinRedirect = signinRedirect
    signoutRedirect = signoutRedirect
    signinSilent = signinSilent
    removeUser = removeUser
  }

  const currentUser = scenario.currentUser ?? {
    subject: 'subject-1',
    username: 'verified.user',
    displayName: '',
    organization: '',
    title: '',
    roles: ['IGNORED_ROLE', 'ASSOCIATION_ADMIN'],
    permissions: ['member:read', 'member:write'],
  }
  const request = vi.fn().mockResolvedValue(currentUser)
  if (scenario.scopedCurrentUser) {
    request.mockResolvedValueOnce(currentUser).mockResolvedValueOnce(scenario.scopedCurrentUser)
  }
  let token: string | null = null
  const setAccessToken = vi.fn((value: string | null) => { token = value })
  const setDemoRole = vi.fn()
  let context = scenario.systemContext ?? {
    associationId: null,
    enterpriseId: null,
  }
  const setSystemContext = vi.fn((associationId: string | null, enterpriseId: string | null) => { context = { associationId, enterpriseId } })
  const getSystemContext = vi.fn(() => ({ ...context }))
  const requestIdentity = vi.fn((path: string, ..._args: unknown[]) => request(path))

  vi.doMock('oidc-client-ts', () => ({
    UserManager: MockUserManager,
    WebStorageStateStore: MockWebStorageStateStore,
  }))
  vi.doMock('./http', () => ({ requestIdentity }))
  vi.doMock('./token-store', () => ({
    getAccessToken: () => token,
    getSystemContext,
    setAccessToken,
    setDemoRole,
    setSystemContext,
  }))

  const module = await import('./auth')
  return {
    auth: module.useAuth(),
    session,
    request,
    requestIdentity,
    setAccessToken,
    setSystemContext,
    getSystemContext,
    getUser,
    signinRedirectCallback,
    signinRedirect,
    signoutRedirect,
    signinSilent,
    removeUser,
    focus: () => events.dispatchEvent(new Event('focus')),
    online: () => events.dispatchEvent(new Event('online')),
    visible: () => documentEvents.dispatchEvent(new Event('visibilitychange')),
    expiring: () => expiringHandler?.(),
    settings: () => managerSettings,
    expire: async () => {
      if (!expiredHandler) throw new Error('expiry handler was not registered')
      expiredHandler()
      await module.useAuth().refreshIdentity().catch(() => {})
    },
  }
}

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.doUnmock('oidc-client-ts')
  vi.doUnmock('./http')
  vi.doUnmock('./token-store')
  vi.restoreAllMocks()
  vi.resetModules()
})

describe('OIDC authentication', () => {
  it('renews an expired stored session before verifying it, without requiring a new login', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: true } })
    await oidc.auth.initialize()
    expect(oidc.signinSilent).toHaveBeenCalledTimes(1)
    expect(oidc.requestIdentity).toHaveBeenCalledWith('/users/me', 'refreshed-token')
    expect(oidc.auth.user.value?.role).toBe('ASSOCIATION_ADMIN')
    expect(oidc.auth.error.value).toBeNull()
  })

  it('coalesces expiry, focus, visibility and concurrent business requests into a single renewal', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false, expires_in: 300 } })
    await oidc.auth.initialize()
    const original = oidc.auth.user.value
    let release!: (value: Record<string, unknown>) => void
    oidc.signinSilent.mockImplementation(() => new Promise(resolve => { release = resolve }))
    oidc.getUser.mockResolvedValue({ access_token: 'old', refresh_token: 'refresh', expired: true })
    const { prepareSession } = await import('./session-gate')
    const requests = Promise.all([prepareSession(), prepareSession(), oidc.auth.refreshIdentity()])
    oidc.focus(); oidc.visible(); oidc.expiring()
    await vi.waitFor(() => expect(oidc.signinSilent).toHaveBeenCalledTimes(1))
    expect(oidc.auth.user.value).toEqual(original)
    expect(oidc.setAccessToken).not.toHaveBeenCalledWith(null)
    release({ access_token: 'new', refresh_token: 'next', expired: false })
    await requests
    expect(oidc.setAccessToken).toHaveBeenLastCalledWith('new')
    expect(oidc.auth.state.sessionIssue).toBeNull()
  })

  it('keeps a verified workspace on temporary renewal failure, blocks requests, and recovers online', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    oidc.getUser.mockResolvedValue({ access_token: 'old', refresh_token: 'refresh', expired: true })
    oidc.signinSilent.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await oidc.expire()
    expect(oidc.auth.user.value).not.toBeNull()
    expect(oidc.auth.state.sessionIssue).toContain('页面内容已保留')
    const { prepareSession } = await import('./session-gate')
    await expect(prepareSession()).rejects.toThrow('暂时无法验证')
    expect(oidc.signinSilent).toHaveBeenCalledTimes(1)
    oidc.online()
    await vi.waitFor(() => expect(oidc.auth.state.sessionIssue).toBeNull())
    expect(oidc.signinSilent).toHaveBeenCalledTimes(2)
    expect(oidc.auth.user.value).not.toBeNull()
  })

  it.each(['invalid_grant', 'login_required', 'interaction_required'])('clears credentials only when the provider confirms %s', async error => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    oidc.signinSilent.mockRejectedValueOnce(Object.assign(new Error('Do not expose provider detail'), { error }))
    await oidc.expire()
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.error.value).toContain('到期或被撤销')
    expect(oidc.auth.error.value).not.toContain('provider detail')
    expect(oidc.removeUser).toHaveBeenCalledTimes(1)
  })

  it('does not clear a verified user when an explicit identity refresh gets HTTP 503', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', expired: false } })
    await oidc.auth.initialize()
    oidc.request.mockRejectedValueOnce(Object.assign(new Error('temporarily unavailable'), { status: 503 }))
    await expect(oidc.auth.refreshIdentity()).rejects.toThrow('temporarily unavailable')
    expect(oidc.auth.user.value).not.toBeNull()
    await oidc.auth.retrySession()
    expect(oidc.auth.state.sessionIssue).toBeNull()
  })

  it('does not resurrect the workspace or transport after logout during renewal', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    let release!: (value: Record<string, unknown>) => void
    oidc.signinSilent.mockImplementation(() => new Promise(resolve => { release = resolve }))
    const expired = oidc.expire()
    await vi.waitFor(() => expect(oidc.signinSilent).toHaveBeenCalledTimes(1))
    await oidc.auth.logout()
    release({ access_token: 'late', expired: false })
    await expired
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.setAccessToken).toHaveBeenLastCalledWith(null)
    expect(oidc.setAccessToken).not.toHaveBeenCalledWith('late')
    expect(oidc.removeUser).toHaveBeenCalledTimes(1)
  })

  it('rechecks backend roles after renewal rather than trusting stale token roles', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    oidc.request.mockResolvedValueOnce({ subject: 'subject-1', username: 'observer', roles: ['OBSERVER'], permissions: ['MEMBER_READ'] })
    await oidc.expire()
    expect(oidc.auth.user.value?.role).toBe('OBSERVER')
  })

  it('a late unauthorized response for an old token cannot expire the renewed session', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    await oidc.expire()
    const { reportUnauthorized } = await import('./session-gate')
    reportUnauthorized('Bearer old')
    await Promise.resolve()
    expect(oidc.signinSilent).toHaveBeenCalledTimes(1)
    expect(oidc.auth.user.value).not.toBeNull()
  })

  it('a current-token HTTP 401 performs one renewal and backend revalidation', async () => {
    const oidc = await loadOidc({ user: { access_token: 'old', refresh_token: 'refresh', expired: false } })
    await oidc.auth.initialize()
    const { reportUnauthorized } = await import('./session-gate')
    reportUnauthorized('Bearer old')
    await vi.waitFor(() => expect(oidc.setAccessToken).toHaveBeenLastCalledWith('refreshed-token'))
    expect(oidc.signinSilent).toHaveBeenCalledTimes(1)
    expect(oidc.requestIdentity).toHaveBeenLastCalledWith('/users/me', 'refreshed-token')
  })

  it('retains delegated scope on HTTP 503, but falls back only when that scope is actually revoked', async () => {
    const associationId = '11111111-1111-4111-8111-111111111111'
    const base = { subject: 's', username: 'system', roles: ['SYSTEM_ADMIN'], permissions: [] }
    const scoped = { ...base, associationId, organization: '协会' }
    const oidc = await loadOidc({ user: { access_token: 'old', expired: false }, systemContext: { associationId, enterpriseId: null }, currentUser: base, scopedCurrentUser: scoped })
    await oidc.auth.initialize()
    oidc.request.mockResolvedValueOnce(base).mockRejectedValueOnce(Object.assign(new Error('server down'), { status: 503 }))
    await expect(oidc.auth.refreshIdentity()).rejects.toThrow('server down')
    expect(oidc.getSystemContext().associationId).toBe(associationId)
    expect(oidc.auth.user.value?.associationId).toBe(associationId)
    oidc.request.mockResolvedValueOnce(base).mockRejectedValueOnce(Object.assign(new Error('revoked'), { status: 403 }))
    await oidc.auth.retrySession()
    expect(oidc.getSystemContext().associationId).toBeNull()
    expect(oidc.auth.user.value?.associationId).toBeUndefined()
    expect(oidc.auth.user.value?.role).toBe('SYSTEM_ADMIN')
  })

  it('does not overwrite a newer delegated scope with a late identity verification', async () => {
    const first = '11111111-1111-4111-8111-111111111111'
    const second = '22222222-2222-4222-8222-222222222222'
    const base = { subject: 's', username: 'system', roles: ['SYSTEM_ADMIN'], permissions: [] }
    const oidc = await loadOidc({ user: { access_token: 'old', expired: false }, systemContext: { associationId: first, enterpriseId: null }, currentUser: base, scopedCurrentUser: { ...base, associationId: first } })
    await oidc.auth.initialize()
    let release!: (value: unknown) => void
    oidc.request.mockResolvedValueOnce(base).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    const checking = oidc.auth.refreshIdentity()
    await vi.waitFor(() => expect(oidc.request).toHaveBeenCalledTimes(4))
    oidc.auth.setSystemContext(second, '第二协会', null)
    release({ ...base, associationId: first })
    await expect(checking).rejects.toThrow('账号或管理范围已改变')
    expect(oidc.auth.user.value?.associationId).toBe(second)
    expect(oidc.getSystemContext().associationId).toBe(second)
    expect(oidc.auth.state.sessionIssue).toBeNull()
  })
  it('changes password only through a fresh PKCE identity-provider action and keeps a safe return path', async () => {
    const oidc = await loadOidc({ user: { access_token: 'test-token', expired: false } })
    await oidc.auth.initialize()
    await oidc.auth.changePassword('/enterprise/profile')
    expect(oidc.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/enterprise/profile' }, prompt: 'login', max_age: 0,
      extraQueryParams: { kc_action: 'UPDATE_PASSWORD' },
    })
    await oidc.auth.changePassword('https://attacker.invalid')
    expect(oidc.signinRedirect).toHaveBeenLastCalledWith(expect.objectContaining({ state: { returnTo: '/' } }))
  })
  it('cannot start password actions without a verified signed-in account', async () => {
    const oidc = await loadOidc()
    await expect(oidc.auth.changePassword()).rejects.toThrow('请使用真实账号登录后修改密码')
    expect(oidc.signinRedirect).not.toHaveBeenCalled()
  })
  it('chooses a stable workspace from verified roles, not their order or token claims', async () => {
    const oidc = await loadOidc({
      user: { access_token: 'test-token', expired: false, profile: { roles: ['SYSTEM_ADMIN'] } },
      currentUser: { subject: 'multi-role', username: 'multi-role', roles: ['ENTERPRISE_MEMBER', 'ASSOCIATION_ADMIN'], permissions: [], associationId: 'a' },
    })
    await oidc.auth.initialize()
    expect(oidc.auth.user.value?.role).toBe('ASSOCIATION_ADMIN')
    expect(oidc.auth.user.value?.role).not.toBe('SYSTEM_ADMIN')
  })

  it('configures authorization-code login and derives identity only from the backend', async () => {
    const oidc = await loadOidc({
      user: {
        access_token: 'access-token-1',
        expired: false,
        state: { returnTo: '/members' },
      },
    })

    expect(oidc.auth.isDemoMode).toBe(false)
    expect(oidc.auth.isInitialized.value).toBe(false)
    expect(oidc.session.getItem(ROLE_STORAGE_KEY)).toBeNull()

    await Promise.all([oidc.auth.initialize(), oidc.auth.initialize()])

    expect(oidc.getUser).toHaveBeenCalledTimes(1)
    expect(oidc.signinRedirectCallback).not.toHaveBeenCalled()
    expect(oidc.settings()).toMatchObject({
      authority: 'https://identity.example.test/realm',
      client_id: 'guanxian-web',
      redirect_uri: 'https://app.example.test/auth/callback',
      post_logout_redirect_uri: 'https://app.example.test/login',
      response_type: 'code',
      scope: 'openid profile email',
      automaticSilentRenew: false,
      monitorSession: false,
      loadUserInfo: false,
    })
    expect(oidc.request).toHaveBeenCalledWith('/users/me')
    expect(oidc.setAccessToken).toHaveBeenCalledWith('access-token-1')
    expect(oidc.auth.user.value).toEqual({
      id: 'subject-1',
      name: 'verified.user',
      role: 'ASSOCIATION_ADMIN',
      organization: '未设置组织',
      title: 'ASSOCIATION_ADMIN',
      permissions: ['member:read', 'member:write'],
    })
    expect(oidc.auth.isAuthenticated.value).toBe(true)
    expect(oidc.auth.isInitialized.value).toBe(true)
    expect(oidc.auth.error.value).toBeNull()
    expect(oidc.auth.takePostLoginRoute()).toBe('/members')
    expect(oidc.auth.takePostLoginRoute()).toBeNull()

    await oidc.expire()
    expect(oidc.setAccessToken).toHaveBeenLastCalledWith(null)
    expect(oidc.setSystemContext).toHaveBeenLastCalledWith(null, null)
    expect(oidc.auth.user.value).toBeNull()
  })

  it('restores a persisted system scope only after validating the base identity and scope', async () => {
    const associationId = '11111111-1111-4111-8111-111111111111'
    const enterpriseId = '22222222-2222-4222-8222-222222222222'
    const oidc = await loadOidc({
      user: { access_token: 'system-token', expired: false },
      systemContext: { associationId, enterpriseId },
      currentUser: {
        subject: 'system-subject',
        username: 'system.admin',
        displayName: '平台管理员',
        organization: '全平台',
        title: '系统管理员',
        roles: ['SYSTEM_ADMIN'],
        permissions: ['system:context:read'],
      },
      scopedCurrentUser: {
        subject: 'system-subject',
        username: 'system.admin',
        displayName: '平台管理员',
        organization: '已验证协会',
        title: '系统管理员',
        roles: ['SYSTEM_ADMIN'],
        permissions: ['system:context:read'],
        associationId,
        enterpriseId,
      },
    })

    await oidc.auth.initialize()

    expect(oidc.request).toHaveBeenCalledTimes(2)
    expect(oidc.setSystemContext.mock.calls).toEqual([
      [associationId, enterpriseId],
    ])
    expect(oidc.requestIdentity.mock.calls).toEqual([
      ['/users/me', 'system-token'],
      ['/users/me', 'system-token', { associationId, enterpriseId }],
    ])
    expect(oidc.auth.user.value).toMatchObject({
      role: 'SYSTEM_ADMIN',
      organization: '已验证协会',
      associationId,
      enterpriseId,
    })
  })

  it('accepts the backend OBSERVER role as a read-only platform identity', async () => {
    const oidc = await loadOidc({
      user: { access_token: 'observer-token', expired: false },
      currentUser: {
        subject: 'observer-subject',
        username: 'observer',
        displayName: '只读观察员',
        organization: '北京地下管线协会',
        title: '只读观察员',
        roles: ['OBSERVER'],
        permissions: ['MEMBER_READ', 'POLICY_READ', 'NOTIFICATION_READ'],
      },
    })

    await oidc.auth.initialize()

    expect(oidc.auth.user.value).toMatchObject({
      role: 'OBSERVER',
      permissions: ['MEMBER_READ', 'POLICY_READ', 'NOTIFICATION_READ'],
    })
    expect(oidc.auth.takePostLoginRoute()).toBe('/members')
  })

  it('does not restore a stored system scope after the account loses the system role', async () => {
    const oidc = await loadOidc({
      user: { access_token: 'downgraded-token', expired: false },
      systemContext: {
        associationId: '11111111-1111-4111-8111-111111111111',
        enterpriseId: null,
      },
    })

    await oidc.auth.initialize()

    expect(oidc.request).toHaveBeenCalledTimes(1)
    expect(oidc.setSystemContext.mock.calls).toEqual([[null, null]])
    expect(oidc.auth.user.value?.role).toBe('ASSOCIATION_ADMIN')
  })

  it('handles every callback path through the OIDC callback validator', async () => {
    const oidc = await loadOidc({
      path: '/auth/callback',
      search: '?error=access_denied&state=signed-state',
      callbackError: new Error('provider rejected login'),
    })

    await oidc.auth.initialize()

    expect(oidc.signinRedirectCallback).toHaveBeenCalledTimes(1)
    expect(oidc.getUser).not.toHaveBeenCalled()
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.isInitialized.value).toBe(true)
    expect(oidc.auth.error.value).toBe('身份验证失败，请重新登录；如持续失败请联系系统管理员检查 OIDC 配置。')
    expect(oidc.setAccessToken).toHaveBeenCalledWith(null)
  })

  it('rejects expired sessions and keeps role-less verified accounts strictly in onboarding', async () => {
    const expired = await loadOidc({
      user: { access_token: 'expired-token', expired: true },
    })
    await expired.auth.initialize()
    expect(expired.request).not.toHaveBeenCalled()
    expect(expired.auth.user.value).toBeNull()
    expect(expired.setAccessToken).toHaveBeenCalledWith(null)

    const unassigned = await loadOidc({
      user: { access_token: 'valid-token', expired: false },
      currentUser: {
        subject: 'subject-2',
        username: 'no.role',
        displayName: '无角色用户',
        organization: '测试组织',
        title: '访客',
        roles: ['UNTRUSTED_ADMIN'],
        permissions: ['*'],
      },
    })
    await unassigned.auth.initialize()
    expect(unassigned.auth.user.value).toBeNull()
    expect(unassigned.auth.error.value).toBeNull()
    expect(unassigned.auth.onboardingIdentity.value?.username).toBe('no.role')
    expect(unassigned.auth.isAuthenticated.value).toBe(false)
    expect(unassigned.request).toHaveBeenCalledWith('/onboarding/session')
    expect(unassigned.setAccessToken.mock.calls).toEqual([
      ['valid-token'],
    ])
  })

  it('a missing binding can only become an onboarding identity after a second backend verification', async () => {
    const oidc = await loadOidc({ user: { access_token: 'onboarding-token', expired: false } })
    oidc.request.mockRejectedValueOnce(Object.assign(new Error('unbound'), { status: 403 }))
      .mockResolvedValueOnce({ subject: 'pending-subject', username: 'pending.user', displayName: '待绑定用户' })
    await oidc.auth.initialize()
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.onboardingIdentity.value?.subject).toBe('pending-subject')
    expect(oidc.auth.takePostLoginRoute()).toBe('/join')
    oidc.request.mockResolvedValueOnce({ subject: 'pending-subject', username: 'pending.user', roles: ['ENTERPRISE_ADMIN'], permissions: ['ENTERPRISE_WRITE'], associationId: 'a', enterpriseId: 'e' })
    await oidc.auth.refreshIdentity()
    expect(oidc.auth.user.value?.enterpriseId).toBe('e')
    expect(oidc.auth.onboardingIdentity.value).toBeNull()
    await oidc.expire()
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.onboardingIdentity.value).toBeNull()
  })

  it.each([401, 403, 500])('clears credentials if onboarding verification returns %s', async status => {
    const oidc = await loadOidc({ user: { access_token: 'pending-token', expired: false } })
    oidc.request.mockRejectedValueOnce(Object.assign(new Error('unbound'), { status: 403 }))
      .mockRejectedValueOnce(Object.assign(new Error('not eligible'), { status }))
    await oidc.auth.initialize()
    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.onboardingIdentity.value).toBeNull()
    expect(oidc.setAccessToken).toHaveBeenLastCalledWith(null)
  })

  it('invalid tokens never fall back to onboarding and pending sessions are cleared on expiry', async () => {
    const oidc = await loadOidc({ user: { access_token: 'invalid-token', expired: false } })
    oidc.request.mockRejectedValueOnce(Object.assign(new Error('invalid token'), { status: 401 }))
    await oidc.auth.initialize()
    expect(oidc.request).toHaveBeenCalledTimes(1)
    expect(oidc.auth.onboardingIdentity.value).toBeNull()
    const pending = await loadOidc({ user: { access_token: 'pending-token', expired: false }, currentUser: { subject:'s', username:'u', roles:[] } })
    await pending.auth.initialize()
    expect(pending.auth.onboardingIdentity.value).not.toBeNull()
    await pending.expire()
    expect(pending.auth.onboardingIdentity.value).toBeNull()
  })

  it('keeps login return paths local and forbids demo switching in OIDC mode', async () => {
    const oidc = await loadOidc()

    await oidc.auth.login('//evil.example/phish')
    await oidc.auth.login('/policies')

    expect(oidc.signinRedirect.mock.calls).toEqual([
      [{ state: { returnTo: '/' } }],
      [{ state: { returnTo: '/policies' } }],
    ])
    expect(() => oidc.auth.loginDemo('SYSTEM_ADMIN')).toThrow('生产认证不允许切换演示身份')
    expect(() => oidc.auth.switchRole('SYSTEM_ADMIN')).toThrow('生产认证不允许切换演示身份')
  })

  it('fails closed when required provider settings are blank', async () => {
    const oidc = await loadOidc({ authority: '   ' })

    await expect(oidc.auth.login('/')).rejects.toThrow('缺少 OIDC 配置：VITE_OIDC_AUTHORITY')
    await oidc.auth.initialize()

    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.auth.error.value).not.toBeNull()
  })

  it('clears local identity before redirecting provider logout', async () => {
    const oidc = await loadOidc({
      user: { access_token: 'logout-token', expired: false },
    })
    await oidc.auth.initialize()

    await oidc.auth.logout()

    expect(oidc.auth.user.value).toBeNull()
    expect(oidc.setAccessToken).toHaveBeenLastCalledWith(null)
    expect(oidc.signoutRedirect).toHaveBeenCalledTimes(1)
  })

  it('normalizes an explicitly local demo mode but never treats oidc mode as demo', async () => {
    vi.resetModules()
    vi.stubEnv('MODE', 'test')
    vi.stubEnv('VITE_AUTH_MODE', ' DEMO ')
    vi.stubGlobal('sessionStorage', createStorage())
    vi.stubGlobal('localStorage', createStorage())

    const module = await import('./auth')
    const auth = module.useAuth()

    expect(auth.isDemoMode).toBe(true)
    expect(auth.loginDemo('ENTERPRISE_MEMBER')).toBe('/enterprise')
  })
})
