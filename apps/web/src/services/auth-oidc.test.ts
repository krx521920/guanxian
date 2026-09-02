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
  vi.stubGlobal('window', {
    location: {
      origin: 'https://app.example.test',
      pathname: scenario.path ?? '/',
      search: scenario.search ?? '',
    },
  })

  let managerSettings: Record<string, unknown> | null = null
  let expiredHandler: (() => void) | null = null
  const getUser = scenario.getUserError
    ? vi.fn().mockRejectedValue(scenario.getUserError)
    : vi.fn().mockResolvedValue(scenario.user ?? null)
  const signinRedirectCallback = scenario.callbackError
    ? vi.fn().mockRejectedValue(scenario.callbackError)
    : vi.fn().mockResolvedValue(scenario.callbackUser ?? null)
  const signinRedirect = vi.fn().mockResolvedValue(undefined)
  const signoutRedirect = vi.fn().mockResolvedValue(undefined)

  class MockWebStorageStateStore {
    constructor(readonly settings: Record<string, unknown>) {}
  }

  class MockUserManager {
    readonly events = {
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
  const setAccessToken = vi.fn()
  const setDemoRole = vi.fn()
  const setSystemContext = vi.fn()
  const getSystemContext = vi.fn(() => scenario.systemContext ?? {
    associationId: null,
    enterpriseId: null,
  })

  vi.doMock('oidc-client-ts', () => ({
    UserManager: MockUserManager,
    WebStorageStateStore: MockWebStorageStateStore,
  }))
  vi.doMock('./http', () => ({ request }))
  vi.doMock('./token-store', () => ({
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
    setAccessToken,
    setSystemContext,
    getSystemContext,
    getUser,
    signinRedirectCallback,
    signinRedirect,
    signoutRedirect,
    settings: () => managerSettings,
    expire: () => {
      if (!expiredHandler) throw new Error('expiry handler was not registered')
      expiredHandler()
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

    oidc.expire()
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
      [null, null],
      [associationId, enterpriseId],
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

  it('rejects expired sessions and backend identities without a platform role', async () => {
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
    expect(unassigned.auth.error.value).not.toBeNull()
    expect(unassigned.setAccessToken.mock.calls).toEqual([
      ['valid-token'],
      [null],
      [null],
    ])
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
