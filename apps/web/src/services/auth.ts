import { computed, reactive, readonly } from 'vue'
import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts'
import { defaultRouteForRole } from '../config/roles'
import { ROLES, type SessionUser, type UserRole } from '../types/domain'
import { request } from './http'
import { setAccessToken } from './token-store'

const ROLE_STORAGE_KEY = 'guanxian.demo.role'
const LEGACY_STORAGE_KEY = 'guanxian.demo.session'
const configuredMode = (import.meta.env.VITE_AUTH_MODE || 'oidc').trim().toLowerCase()
const demoMode = import.meta.env.MODE !== 'production' && configuredMode === 'demo'

const demoUsers: Record<UserRole, SessionUser> = {
  SYSTEM_ADMIN: { id: 'u-001', name: '平台管理员', role: 'SYSTEM_ADMIN', organization: '管线智联平台', title: '系统管理员', permissions: [] },
  ASSOCIATION_ADMIN: { id: 'u-002', name: '张全超', role: 'ASSOCIATION_ADMIN', organization: '北京地下管线协会', title: '协会管理员', permissions: [] },
  ASSOCIATION_OPERATOR: { id: 'u-003', name: '徐明', role: 'ASSOCIATION_OPERATOR', organization: '北京地下管线协会', title: '会员服务专员', permissions: [] },
  ENTERPRISE_ADMIN: { id: 'u-004', name: '王志远', role: 'ENTERPRISE_ADMIN', organization: '京城管网科技有限公司', title: '企业管理员', permissions: [] },
  ENTERPRISE_MEMBER: { id: 'u-005', name: '李楠', role: 'ENTERPRISE_MEMBER', organization: '京城管网科技有限公司', title: '市场经理', permissions: [] },
}

interface CurrentUserView {
  subject: string
  username: string
  displayName: string
  organization: string
  title: string
  roles: string[]
  permissions: string[]
}

interface RedirectState {
  returnTo?: unknown
}

function isUserRole(value: string | null | undefined): value is UserRole {
  return Boolean(value && ROLES.includes(value as UserRole))
}

function safeLocalPath(value: unknown, fallback = '/'): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : fallback
}

function clearLegacySession() {
  try {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  } catch {
    // Storage can be unavailable in privacy modes.
  }
}

function loadDemoSession(): SessionUser | null {
  clearLegacySession()
  if (!demoMode) {
    try {
      sessionStorage.removeItem(ROLE_STORAGE_KEY)
    } catch {
      // OIDC remains usable with the provider's own storage fallback.
    }
    return null
  }
  try {
    const role = sessionStorage.getItem(ROLE_STORAGE_KEY)
    return isUserRole(role) ? demoUsers[role] : null
  } catch {
    return null
  }
}

const state = reactive<{
  user: SessionUser | null
  initialized: boolean
  error: string | null
  postLoginRoute: string | null
}>({
  user: loadDemoSession(),
  initialized: demoMode,
  error: null,
  postLoginRoute: null,
})

let manager: UserManager | null = null
let initialization: Promise<void> | null = null

function requiredOidcSetting(name: string, value: string | undefined): string {
  if (!value?.trim()) throw new Error(`缺少 OIDC 配置：${name}`)
  return value.trim()
}

function userManager(): UserManager {
  if (manager) return manager
  const origin = window.location.origin
  const settings: UserManagerSettings = {
    authority: requiredOidcSetting('VITE_OIDC_AUTHORITY', import.meta.env.VITE_OIDC_AUTHORITY),
    client_id: requiredOidcSetting('VITE_OIDC_CLIENT_ID', import.meta.env.VITE_OIDC_CLIENT_ID),
    redirect_uri: import.meta.env.VITE_OIDC_REDIRECT_URI || `${origin}/auth/callback`,
    post_logout_redirect_uri: import.meta.env.VITE_OIDC_POST_LOGOUT_REDIRECT_URI || `${origin}/login`,
    response_type: 'code',
    scope: import.meta.env.VITE_OIDC_SCOPE || 'openid profile email',
    automaticSilentRenew: false,
    monitorSession: false,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }
  manager = new UserManager(settings)
  manager.events.addAccessTokenExpired(() => {
    setAccessToken(null)
    state.user = null
  })
  return manager
}

function setDemoUser(user: SessionUser | null) {
  state.user = user
  clearLegacySession()
  try {
    if (user) sessionStorage.setItem(ROLE_STORAGE_KEY, user.role)
    else sessionStorage.removeItem(ROLE_STORAGE_KEY)
  } catch {
    // Do not make local demo login unusable when browser storage is blocked.
  }
}

async function loadVerifiedUser(oidcUser: User): Promise<void> {
  if (!oidcUser.access_token || oidcUser.expired) {
    setAccessToken(null)
    state.user = null
    return
  }
  setAccessToken(oidcUser.access_token)
  const verified = await request<CurrentUserView>('/users/me')
  const role = verified.roles.find(isUserRole)
  if (!role) {
    setAccessToken(null)
    state.user = null
    throw new Error('当前账号没有平台角色')
  }
  state.user = {
    id: verified.subject,
    name: verified.displayName || verified.username,
    role,
    organization: verified.organization || '未设置组织',
    title: verified.title || role,
    permissions: verified.permissions,
  }
  state.postLoginRoute = safeLocalPath(
    (oidcUser.state as RedirectState | null)?.returnTo,
    defaultRouteForRole(role),
  )
}

async function initializeOidc(): Promise<void> {
  if (state.initialized) return
  if (initialization) return initialization

  initialization = (async () => {
    state.error = null
    try {
      const oidc = userManager()
      const callback = window.location.pathname === '/auth/callback'
      const user = callback ? await oidc.signinRedirectCallback() : await oidc.getUser()
      if (user) await loadVerifiedUser(user)
    } catch {
      setAccessToken(null)
      state.user = null
      state.error = '身份验证失败，请重新登录；如持续失败请联系系统管理员检查 OIDC 配置。'
    } finally {
      state.initialized = true
      initialization = null
    }
  })()
  return initialization
}

export function useAuth() {
  return {
    state: readonly(state),
    user: computed(() => state.user),
    isAuthenticated: computed(() => Boolean(state.user)),
    isInitialized: computed(() => state.initialized),
    error: computed(() => state.error),
    isDemoMode: demoMode,
    demoUsers,
    initialize: demoMode ? async () => undefined : initializeOidc,
    async login(returnTo = '/') {
      if (demoMode) throw new Error('演示模式应使用 loginDemo')
      state.error = null
      await userManager().signinRedirect({ state: { returnTo: safeLocalPath(returnTo) } })
    },
    loginDemo(role: UserRole) {
      if (!demoMode) throw new Error('生产认证不允许切换演示身份')
      setDemoUser(demoUsers[role])
      return defaultRouteForRole(role)
    },
    switchRole(role: UserRole) {
      if (!demoMode) throw new Error('生产认证不允许切换演示身份')
      setDemoUser(demoUsers[role])
      return defaultRouteForRole(role)
    },
    takePostLoginRoute() {
      const route = state.postLoginRoute
      state.postLoginRoute = null
      return route
    },
    async logout() {
      setAccessToken(null)
      state.user = null
      if (demoMode) {
        setDemoUser(null)
        return
      }
      await userManager().signoutRedirect()
    },
  }
}
