import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserRole } from '../types/domain'

const ROLE_STORAGE_KEY = 'guanxian.demo.role'
const LEGACY_STORAGE_KEY = 'guanxian.demo.session'

function createStorage(initial?: Record<string, string>): Storage {
  const entries = new Map(Object.entries(initial ?? {}))
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

async function loadAuth(session = createStorage(), legacy = createStorage()) {
  vi.resetModules()
  vi.stubGlobal('sessionStorage', session)
  vi.stubGlobal('localStorage', legacy)
  const module = await import('./auth')
  return { auth: module.useAuth(), session, legacy }
}

describe('useAuth', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it.each<[UserRole, string]>([
    ['SYSTEM_ADMIN', '/association'],
    ['ASSOCIATION_ADMIN', '/association'],
    ['ASSOCIATION_OPERATOR', '/association'],
    ['ENTERPRISE_ADMIN', '/enterprise'],
    ['ENTERPRISE_MEMBER', '/enterprise'],
  ])('logs %s in and returns its authorized workspace', async (role, expectedRoute) => {
    const { auth, session } = await loadAuth()

    expect(auth.login(role)).toBe(expectedRoute)
    expect(auth.isAuthenticated.value).toBe(true)
    expect(auth.user.value?.role).toBe(role)
    expect(session.getItem(ROLE_STORAGE_KEY)).toBe(role)
  })

  it('provides complete deterministic demo identities for every role', async () => {
    const { auth } = await loadAuth()

    expect(auth.demoUsers).toEqual({
      SYSTEM_ADMIN: { id: 'u-001', name: '平台管理员', role: 'SYSTEM_ADMIN', organization: '管线智联平台', title: '系统管理员' },
      ASSOCIATION_ADMIN: { id: 'u-002', name: '张全超', role: 'ASSOCIATION_ADMIN', organization: '北京地下管线协会', title: '协会管理员' },
      ASSOCIATION_OPERATOR: { id: 'u-003', name: '徐明', role: 'ASSOCIATION_OPERATOR', organization: '北京地下管线协会', title: '会员服务专员' },
      ENTERPRISE_ADMIN: { id: 'u-004', name: '王志远', role: 'ENTERPRISE_ADMIN', organization: '京城管网科技有限公司', title: '企业管理员' },
      ENTERPRISE_MEMBER: { id: 'u-005', name: '李楠', role: 'ENTERPRISE_MEMBER', organization: '京城管网科技有限公司', title: '市场经理' },
    })
  })

  it('switches identities and persists the new account', async () => {
    const { auth, session } = await loadAuth()
    auth.login('ASSOCIATION_OPERATOR')

    expect(auth.switchRole('ENTERPRISE_ADMIN')).toBe('/enterprise')
    expect(auth.user.value?.organization).toBe('京城管网科技有限公司')
    expect(session.getItem(ROLE_STORAGE_KEY)).toBe('ENTERPRISE_ADMIN')
  })

  it('logs out and removes the persisted session', async () => {
    const { auth, session } = await loadAuth()
    auth.login('ASSOCIATION_ADMIN')

    auth.logout()

    expect(auth.user.value).toBeNull()
    expect(auth.isAuthenticated.value).toBe(false)
    expect(session.getItem(ROLE_STORAGE_KEY)).toBeNull()
  })

  it('restores a whitelisted role by deriving its trusted demo identity', async () => {
    const session = createStorage({ [ROLE_STORAGE_KEY]: 'ENTERPRISE_MEMBER' })

    const { auth } = await loadAuth(session)

    expect(auth.isAuthenticated.value).toBe(true)
    expect(auth.user.value).toEqual({
      id: 'u-005',
      name: '李楠',
      role: 'ENTERPRISE_MEMBER',
      organization: '京城管网科技有限公司',
      title: '市场经理',
    })
  })

  it.each(['ADMIN', '__proto__', '{"role":"SYSTEM_ADMIN"}', ''])('rejects forged stored role %j', async (role) => {
    const session = createStorage({ [ROLE_STORAGE_KEY]: role })

    const { auth } = await loadAuth(session)

    expect(auth.user.value).toBeNull()
    expect(auth.isAuthenticated.value).toBe(false)
  })

  it('purges the legacy full-profile localStorage value without trusting it', async () => {
    const legacy = createStorage({
      [LEGACY_STORAGE_KEY]: JSON.stringify({ role: 'SYSTEM_ADMIN', token: 'must-not-survive' }),
    })

    const { auth } = await loadAuth(createStorage(), legacy)

    expect(auth.user.value).toBeNull()
    expect(legacy.getItem(LEGACY_STORAGE_KEY)).toBeNull()
  })

  it('keeps in-memory login usable when session storage is blocked', async () => {
    const blockedStorage = createStorage()
    vi.spyOn(blockedStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    vi.spyOn(blockedStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    const { auth } = await loadAuth(blockedStorage)

    expect(auth.user.value).toBeNull()
    expect(() => auth.login('ASSOCIATION_OPERATOR')).not.toThrow()
    expect(auth.user.value?.role).toBe('ASSOCIATION_OPERATOR')
    expect(auth.isAuthenticated.value).toBe(true)
  })
})
