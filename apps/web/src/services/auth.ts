import { computed, reactive, readonly } from 'vue'
import { defaultRouteForRole } from '../config/roles'
import { ROLES, type SessionUser, type UserRole } from '../types/domain'

const ROLE_STORAGE_KEY = 'guanxian.demo.role'
const LEGACY_STORAGE_KEY = 'guanxian.demo.session'

const demoUsers: Record<UserRole, SessionUser> = {
  SYSTEM_ADMIN: { id: 'u-001', name: '平台管理员', role: 'SYSTEM_ADMIN', organization: '管线智联平台', title: '系统管理员' },
  ASSOCIATION_ADMIN: { id: 'u-002', name: '张全超', role: 'ASSOCIATION_ADMIN', organization: '北京地下管线协会', title: '协会管理员' },
  ASSOCIATION_OPERATOR: { id: 'u-003', name: '徐明', role: 'ASSOCIATION_OPERATOR', organization: '北京地下管线协会', title: '会员服务专员' },
  ENTERPRISE_ADMIN: { id: 'u-004', name: '王志远', role: 'ENTERPRISE_ADMIN', organization: '京城管网科技有限公司', title: '企业管理员' },
  ENTERPRISE_MEMBER: { id: 'u-005', name: '李楠', role: 'ENTERPRISE_MEMBER', organization: '京城管网科技有限公司', title: '市场经理' },
}

function isUserRole(value: string | null): value is UserRole {
  return ROLES.includes(value as UserRole)
}

function clearLegacySession() {
  try {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  } catch {
    // Storage can be unavailable in privacy modes; authentication state remains in memory.
  }
}

function loadSession(): SessionUser | null {
  clearLegacySession()
  try {
    const role = sessionStorage.getItem(ROLE_STORAGE_KEY)
    return isUserRole(role) ? demoUsers[role] : null
  } catch {
    return null
  }
}

const state = reactive<{ user: SessionUser | null }>({ user: loadSession() })

function setUser(user: SessionUser | null) {
  state.user = user
  clearLegacySession()
  try {
    if (user) sessionStorage.setItem(ROLE_STORAGE_KEY, user.role)
    else sessionStorage.removeItem(ROLE_STORAGE_KEY)
  } catch {
    // Do not make login/logout unusable when browser storage is blocked.
  }
}

export function useAuth() {
  return {
    state: readonly(state),
    user: computed(() => state.user),
    isAuthenticated: computed(() => Boolean(state.user)),
    demoUsers,
    login(role: UserRole) {
      setUser(demoUsers[role])
      return defaultRouteForRole(role)
    },
    switchRole(role: UserRole) {
      setUser(demoUsers[role])
      return defaultRouteForRole(role)
    },
    logout() {
      setUser(null)
    },
  }
}
