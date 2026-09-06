import type { RouteLocationNormalized, RouteLocationResolved, Router } from 'vue-router'
import { defaultRouteForRole, enterpriseRoles, hasAnyRole } from '../config/roles'
import { safeLocalPath } from '../services/local-path'
import type { SessionUser } from '../types/domain'

export function workspaceForUser(user: SessionUser): string {
  if (enterpriseRoles.includes(user.role) && (!user.associationId || !user.enterpriseId)) return '/access-help'
  if (user.role !== 'SYSTEM_ADMIN' && !user.associationId) return '/access-help'
  return defaultRouteForRole(user.role)
}

function canEnter(user: SessionUser, to: RouteLocationNormalized | RouteLocationResolved): boolean {
  if (!hasAnyRole(user.role, to.meta.roles)) return false
  // The backend remains authoritative; do not even mount another enterprise's editor.
  return !(user.role === 'ENTERPRISE_ADMIN' && to.name === 'member-edit'
    && to.params.id !== user.enterpriseId)
}

export function postLoginDestination(router: Router, user: SessionUser, requested?: unknown): string {
  const home = workspaceForUser(user)
  if (home === '/access-help') return home
  const path = safeLocalPath(requested)
  const destination = router.resolve(path)
  return canEnter(user, destination) ? destination.fullPath : home
}

interface RoutingAuth {
  user: { readonly value: SessionUser | null }
  onboardingIdentity?: { readonly value: unknown }
  initialize(): Promise<void>
  takePostLoginRoute(): string | null
}

export function installAccessGuard(router: Router, auth: RoutingAuth) {
  router.beforeEach(async (to) => {
    // Public browsing must not depend on OIDC availability or fetch /users/me.
    if (to.meta.public) return true
    await auth.initialize()
    const user = auth.user.value
    const onboarding = Boolean(auth.onboardingIdentity?.value)

    // A pending identity can only confirm its invitation, not mount the internal shell.
    if (to.path === '/join') return true

    if (to.path === '/auth/callback') {
      return user ? postLoginDestination(router, user, auth.takePostLoginRoute()) : onboarding ? '/join' : '/login'
    }
    if (to.path === '/' || to.path === '/login') {
      return user ? postLoginDestination(router, user, to.query.redirect) : onboarding ? '/join' : true
    }
    if (!user) return onboarding ? '/join' : { path: '/login', query: { redirect: to.fullPath } }

    const home = workspaceForUser(user)
    if (to.path === '/access-help') return home === '/access-help' ? true : home
    if (home === '/access-help') return home
    if (!canEnter(user, to)) return home
    return true
  })
}
