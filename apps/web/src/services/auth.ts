import { computed, reactive, readonly } from 'vue'
import {
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts'
import { defaultRouteForRole } from '../config/roles'
import { ROLES, type SessionUser, type UserRole } from '../types/domain'
import { requestIdentity } from './http'
import { safeLocalPath } from './local-path'
import { changeSessionBoundary, configureSessionGate, sessionBoundary } from './session-gate'
import {
  getAccessToken,
  getSystemContext,
  setAccessToken,
  setDemoRole,
  setSystemContext as setTransportSystemContext,
} from './token-store'

const ROLE_STORAGE_KEY = 'guanxian.demo.role'
const LEGACY_STORAGE_KEY = 'guanxian.demo.session'
const configuredMode = (import.meta.env.VITE_AUTH_MODE || 'oidc').trim().toLowerCase()
const demoMode = import.meta.env.MODE !== 'production' && configuredMode === 'demo'

const demoUsers: Record<UserRole, SessionUser> = {
  SYSTEM_ADMIN: { id: 'u-001', name: '平台管理员', role: 'SYSTEM_ADMIN', organization: '管线智联平台', title: '系统管理员', permissions: [], associationId: '00000000-0000-0000-0000-000000000106' },
  ASSOCIATION_ADMIN: { id: 'u-002', name: '张全超', role: 'ASSOCIATION_ADMIN', organization: '北京地下管线协会', title: '协会管理员', permissions: [], associationId: '00000000-0000-0000-0000-000000000106' },
  ASSOCIATION_OPERATOR: { id: 'u-003', name: '徐明', role: 'ASSOCIATION_OPERATOR', organization: '北京地下管线协会', title: '会员服务专员', permissions: [], associationId: '00000000-0000-0000-0000-000000000106' },
  ENTERPRISE_ADMIN: { id: 'u-004', name: '王志远', role: 'ENTERPRISE_ADMIN', organization: '京城管网科技有限公司', title: '企业管理员', permissions: [], associationId: '00000000-0000-0000-0000-000000000106', enterpriseId: '00000000-0000-0000-0000-000000000201' },
  ENTERPRISE_MEMBER: { id: 'u-005', name: '李楠', role: 'ENTERPRISE_MEMBER', organization: '京城管网科技有限公司', title: '市场经理', permissions: [], associationId: '00000000-0000-0000-0000-000000000106', enterpriseId: '00000000-0000-0000-0000-000000000201' },
  OBSERVER: { id: 'u-006', name: '只读观察员', role: 'OBSERVER', organization: '北京地下管线协会', title: '只读观察员', permissions: ['MEMBER_READ', 'POLICY_READ', 'NOTIFICATION_READ'], associationId: '00000000-0000-0000-0000-000000000106' },
}

interface CurrentUserView {
  subject: string
  username: string
  displayName: string
  organization: string
  title: string
  roles: string[]
  permissions: string[]
  associationId?: string | null
  enterpriseId?: string | null
}

interface RedirectState {
  returnTo?: unknown
}

function isUserRole(value: string | null | undefined): value is UserRole {
  return Boolean(value && ROLES.includes(value as UserRole))
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
  onboardingIdentity: { subject: string; username: string; displayName: string } | null
  initialized: boolean
  error: string | null
  postLoginRoute: string | null
  sessionIssue: string | null
  recovering: boolean
}>({
  user: loadDemoSession(),
  onboardingIdentity: null,
  initialized: demoMode,
  error: null,
  postLoginRoute: null,
  sessionIssue: null,
  recovering: false,
})
setDemoRole(demoMode ? state.user?.role ?? null : null)
if (demoMode) {
  setTransportSystemContext(
    state.user?.role === 'SYSTEM_ADMIN' ? state.user.associationId || null : null,
    state.user?.role === 'SYSTEM_ADMIN' ? state.user.enterpriseId || null : null,
  )
}

let manager: UserManager | null = null
let initialization: Promise<void> | null = null
let recovery: Promise<void> | null = null
let revision = 0
let closing = false
let retryAfter = 0
let needsVerification = false
let renewalRequired = false

const endedMessage = '登录会话已到期或被撤销，请重新登录后继续。'
const deniedMessage = '账号没有平台访问权限，或尚未完成组织绑定。请联系协会管理员核验账号。'
class SessionEnded extends Error {}
class SessionChanged extends Error {}

function clearIdentity(message?: string) {
  revision += 1
  changeSessionBoundary()
  setAccessToken(null)
  setTransportSystemContext(null, null)
  state.user = null
  state.onboardingIdentity = null
  state.sessionIssue = null
  state.error = message ?? null
}

function isSessionEnded(error: unknown) {
  if (error instanceof SessionEnded) return true
  if (!error || typeof error !== 'object') return false
  const value = error as { status?: number; error?: string }
  return value.status === 401 || value.status === 403
    || ['invalid_grant', 'login_required', 'interaction_required'].includes(value.error || '')
}

function assertCurrent(started: number, boundary: number) {
  if (closing || started !== revision || boundary !== sessionBoundary()) {
    throw new SessionChanged('账号或管理范围已改变，请重试。')
  }
}

// All expiry timers, wake events and HTTP callers share ONE refresh-token request.
// Running oidc-client-ts automatic renewal as well would race token rotation.
function ensureSession(verify = false, renew = false): Promise<void> {
  if (demoMode) return Promise.resolve()
  if (closing) return Promise.reject(new SessionEnded(endedMessage))
  if (verify) needsVerification = true
  if (renew) renewalRequired = true
  if (recovery) return recovery
  if (state.sessionIssue && Date.now() < retryAfter) return Promise.reject(new Error(state.sessionIssue))
  const started = revision
  const boundary = sessionBoundary()
  recovery = (async () => {
    try {
      const oidc = userManager()
      let user = await oidc.getUser()
      assertCurrent(started, boundary)
      if (!user?.access_token) {
        if (state.user || state.onboardingIdentity) throw new SessionEnded(endedMessage)
        return
      }
      const expiring = user.expired || (user.expires_in !== undefined && user.expires_in <= 60)
      if (renewalRequired || expiring) {
        if (user.refresh_token) {
          state.recovering = true
          renewalRequired = false
          user = await oidc.signinSilent()
          // signinSilent stores its result before returning. Never resurrect a logout.
          if (closing || started !== revision) {
            if (closing) await oidc.removeUser()
            throw new SessionChanged('登录状态已改变')
          }
          verify = true
        } else if (user.expired || renewalRequired) {
          throw new SessionEnded(endedMessage)
        }
      }
      assertCurrent(started, boundary)
      if (!user?.access_token || user.expired) throw new SessionEnded(endedMessage)
      if (verify || needsVerification || state.sessionIssue || (!state.user && !state.onboardingIdentity)) {
        state.recovering = true
        await loadVerifiedUser(user, started, boundary)
      }
      state.sessionIssue = null
      state.error = null
      needsVerification = false
      retryAfter = 0
    } catch (error) {
      if (error instanceof SessionChanged || started !== revision || closing) throw error
      if (isSessionEnded(error)) {
        clearIdentity(error instanceof Error && 'status' in error && error.status === 403 ? deniedMessage : endedMessage)
        await manager?.removeUser()
      } else {
        // Keep the last verified workspace mounted (including unsaved form state),
        // but gate every new business request until identity verification recovers.
        needsVerification = true
        state.sessionIssue = '暂时无法验证登录状态，可能是网络或认证服务中断。页面内容已保留，恢复连接后可重试。'
        retryAfter = Date.now() + 10000
      }
      throw error
    } finally {
      recovery = null
      state.recovering = false
    }
  })()
  return recovery
}

function recoverInBackground(verify = false, renew = false) {
  if (!state.initialized || (!state.user && !state.onboardingIdentity)) return
  void ensureSession(verify, renew).catch(() => { /* Explicit recovery UI or login reason handles failures. */ })
}

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
    accessTokenExpiringNotificationTimeInSeconds: 60,
    silentRequestTimeoutInSeconds: 15,
    requestTimeoutInSeconds: 15,
    monitorSession: false,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }
  manager = new UserManager(settings)
  manager.events.addAccessTokenExpiring(() => recoverInBackground(false, false))
  manager.events.addAccessTokenExpired(() => recoverInBackground(false, true))
  configureSessionGate(async () => {
    await ensureSession()
    if (!getAccessToken()) throw new SessionEnded(endedMessage)
  }, authorization => {
    // An old in-flight response must not invalidate a newer token or account.
    if (authorization && authorization === `Bearer ${getAccessToken()}`) recoverInBackground(false, true)
  })
  const wake = () => recoverInBackground(true)
  window.addEventListener('focus', wake)
  window.addEventListener('online', () => { retryAfter = 0; wake() })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') wake()
  })
  return manager
}

function setDemoUser(user: SessionUser | null) {
  state.user = user
  setDemoRole(user?.role ?? null)
  setTransportSystemContext(
    user?.role === 'SYSTEM_ADMIN' ? user.associationId || null : null,
    user?.role === 'SYSTEM_ADMIN' ? user.enterpriseId || null : null,
  )
  clearLegacySession()
  try {
    if (user) sessionStorage.setItem(ROLE_STORAGE_KEY, user.role)
    else sessionStorage.removeItem(ROLE_STORAGE_KEY)
  } catch {
    // Do not make local demo login unusable when browser storage is blocked.
  }
}

async function loadVerifiedUser(oidcUser: User, started = revision, boundary = sessionBoundary()): Promise<void> {
  if (!oidcUser.access_token || oidcUser.expired) {
    throw new SessionEnded(endedMessage)
  }
  const persistedContext = getSystemContext()
  // A persisted system-admin context must never influence the initial identity
  // verification. Restore it only after the backend has confirmed the account
  // is still a system administrator, then validate the selected scope again.
  let baseIdentity: CurrentUserView
  try {
    baseIdentity = await requestIdentity<CurrentUserView>('/users/me', oidcUser.access_token)
  } catch (error) {
    if (error instanceof Error && 'status' in error && error.status === 403) {
      await loadOnboardingIdentity(oidcUser, started, boundary)
      return
    }
    throw error
  }
  // Stable workspace precedence uses only backend-verified roles, never login-card selection.
  const role = ROLES.find((candidate) => baseIdentity.roles.includes(candidate))
  if (!role) {
    await loadOnboardingIdentity(oidcUser, started, boundary)
    return
  }
  let verified = baseIdentity
  let verifiedContext = { associationId: null, enterpriseId: null } as ReturnType<typeof getSystemContext>
  if (role === 'SYSTEM_ADMIN' && persistedContext.associationId) {
    try {
      const scopedIdentity = await requestIdentity<CurrentUserView>('/users/me', oidcUser.access_token, persistedContext)
      const contextMatches = scopedIdentity.roles.includes('SYSTEM_ADMIN')
        && scopedIdentity.subject === baseIdentity.subject
        && scopedIdentity.associationId === persistedContext.associationId
        && (persistedContext.enterpriseId === null
          || scopedIdentity.enterpriseId === persistedContext.enterpriseId)
      if (contextMatches) {
        verified = scopedIdentity
        verifiedContext = persistedContext
      }
    } catch (error) {
      // Losing an old delegated scope must not invalidate the administrator's
      // base login. Fall back to the unscoped platform identity.
      if (!(error instanceof Error && 'status' in error && error.status === 403)) throw error
    }
  }
  assertCurrent(started, boundary)
  const nextIdentity = { id: verified.subject, role, associationId: verified.associationId, enterpriseId: verified.enterpriseId }
  const previousIdentity = state.user && { id: state.user.id, role: state.user.role, associationId: state.user.associationId, enterpriseId: state.user.enterpriseId }
  if (JSON.stringify(previousIdentity) !== JSON.stringify(nextIdentity)
    || JSON.stringify(persistedContext) !== JSON.stringify(verifiedContext)) changeSessionBoundary()
  setAccessToken(oidcUser.access_token)
  setTransportSystemContext(verifiedContext.associationId, verifiedContext.enterpriseId)
  state.onboardingIdentity = null
  state.user = {
    id: verified.subject,
    name: verified.displayName || verified.username,
    role,
    organization: verified.organization || '未设置组织',
    title: verified.title || role,
    permissions: verified.permissions,
    associationId: verified.associationId,
    enterpriseId: verified.enterpriseId,
  }
  state.postLoginRoute = safeLocalPath(
    (oidcUser.state as RedirectState | null)?.returnTo,
    defaultRouteForRole(role),
  )
}

async function loadOnboardingIdentity(oidcUser: User, started: number, boundary: number) {
  const identity = await requestIdentity<{ subject: string; username: string; displayName: string }>('/onboarding/session', oidcUser.access_token)
  if (!identity?.subject || !identity.username) throw new Error('无法核验待绑定账号')
  assertCurrent(started, boundary)
  if (state.user || identity.subject !== state.onboardingIdentity?.subject) changeSessionBoundary()
  setAccessToken(oidcUser.access_token)
  state.user = null
  setTransportSystemContext(null, null)
  // This is not a SessionUser: onboarding never unlocks business routes or operations.
  state.onboardingIdentity = identity
  state.postLoginRoute = '/join'
}

async function initializeOidc(): Promise<void> {
  if (state.initialized) return
  if (initialization) return initialization

  initialization = (async () => {
    state.error = null
    try {
      const oidc = userManager()
      const callback = window.location.pathname === '/auth/callback'
      if (callback) {
        const user = await oidc.signinRedirectCallback()
        if (user) await loadVerifiedUser(user)
      } else await ensureSession(true)
    } catch (error) {
      const explanation = error instanceof Error && 'status' in error && error.status === 403
        ? deniedMessage
        : isSessionEnded(error) ? endedMessage
        : '身份验证失败，请重新登录；如持续失败请联系系统管理员检查 OIDC 配置。'
      clearIdentity(explanation)
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
    onboardingIdentity: computed(() => state.onboardingIdentity),
    isAuthenticated: computed(() => Boolean(state.user)),
    isInitialized: computed(() => state.initialized),
    error: computed(() => state.error),
    isDemoMode: demoMode,
    demoUsers,
    initialize: demoMode ? async () => undefined : initializeOidc,
    async retrySession() {
      retryAfter = 0
      await ensureSession(true)
    },
    async refreshIdentity() {
      if (demoMode) return
      await ensureSession(true)
    },
    async login(returnTo = '/') {
      if (demoMode) throw new Error('演示模式应使用 loginDemo')
      state.error = null
      await userManager().signinRedirect({ state: { returnTo: safeLocalPath(returnTo) } })
    },
    async changePassword(returnTo = '/') {
      if (demoMode || !state.user) throw new Error('请使用真实账号登录后修改密码')
      // Keycloak handles the old/new password, PKCE, state and reauthentication.
      // No password is collected by our application or passed in a URL.
      await userManager().signinRedirect({
        state: { returnTo: safeLocalPath(returnTo) },
        prompt: 'login', max_age: 0, extraQueryParams: { kc_action: 'UPDATE_PASSWORD' },
      })
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
    setSystemContext(associationId: string | null, associationName: string, enterpriseId: string | null) {
      if (state.user?.role !== 'SYSTEM_ADMIN') throw new Error('仅系统管理员可切换管理上下文')
      const previous = getSystemContext()
      if (previous.associationId !== associationId || previous.enterpriseId !== enterpriseId) changeSessionBoundary()
      setTransportSystemContext(associationId, enterpriseId)
      state.user = {
        ...state.user,
        associationId,
        enterpriseId,
        organization: associationId ? associationName : '全平台',
      }
    },
    takePostLoginRoute() {
      const route = state.postLoginRoute
      state.postLoginRoute = null
      return route
    },
    async logout() {
      closing = !demoMode
      clearIdentity()
      if (demoMode) {
        setDemoUser(null)
        return
      }
      await userManager().signoutRedirect()
    },
  }
}
