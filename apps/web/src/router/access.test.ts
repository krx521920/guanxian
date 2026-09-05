import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ROLES, type SessionUser, type UserRole } from '../types/domain'
import { safeLocalPath } from '../services/local-path'
import { installAccessGuard, postLoginDestination, workspaceForUser } from './access'
import { protectedRouteRoles } from './permissions'

function user(role: UserRole, overrides: Partial<SessionUser> = {}): SessionUser {
  return { id: 'verified-user', name: '测试账号', role, organization: '测试组织', title: role,
    associationId: 'association-a', enterpriseId: role.startsWith('ENTERPRISE_') ? 'enterprise-a' : null,
    permissions: [], ...overrides }
}

function harness(identity: SessionUser | null = null, returnTo: string | null = null) {
  const component = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', alias: '/', component },
      { path: '/public', component, meta: { public: true } },
      { path: '/auth/callback', component },
      { path: '/access-help', component },
      { path: '/join', component },
      { path: '/workspace', component },
      ...Object.entries(protectedRouteRoles).map(([path, roles]) => ({
        path: path === '/members/edit' ? '/members/:id/edit' : path,
        name: path === '/members/edit' ? 'member-edit' : path,
        component, meta: { roles },
      })),
      { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
  })
  const auth = { user: { value: identity }, onboardingIdentity: { value: null as unknown }, initialize: vi.fn(async () => undefined), takePostLoginRoute: vi.fn(() => returnTo) }
  installAccessGuard(router, auth)
  return { router, auth }
}

describe('unified entry and verified identity routing', () => {
  it('pending identities can confirm invitations but cannot mount any business workspace', async () => {
    const { router, auth } = harness()
    auth.onboardingIdentity.value = { subject: 'pending', username: 'owner' }
    for (const path of ['/', '/auth/callback', '/operations', '/enterprise/profile', '/members']) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe('/join')
    }
    await router.push('/public')
    expect(router.currentRoute.value.path).toBe('/public')
  })
  it.each<[UserRole, string]>([
    ['SYSTEM_ADMIN', '/association'], ['ASSOCIATION_ADMIN', '/association'], ['ASSOCIATION_OPERATOR', '/association'],
    ['ENTERPRISE_ADMIN', '/enterprise'], ['ENTERPRISE_MEMBER', '/enterprise'], ['OBSERVER', '/members'],
  ])('sends verified %s to %s from the entry and callback', async (role, home) => {
    for (const path of ['/', '/login?entry=admin', '/auth/callback', '/workspace']) {
      const { router } = harness(user(role))
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(home)
    }
  })

  it('serves the public portal without initializing identity, even for a stored user', async () => {
    for (const identity of [null, user('SYSTEM_ADMIN')]) {
      const { router, auth } = harness(identity)
      auth.initialize.mockRejectedValue(new Error('provider unavailable'))
      await router.push('/public')
      expect(router.currentRoute.value.path).toBe('/public')
      expect(auth.initialize).not.toHaveBeenCalled()
    }
  })

  it('keeps anonymous entry usable and requires login for every protected route', async () => {
    const { router } = harness()
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
    for (const path of Object.keys(protectedRouteRoles)) {
      const target = path === '/members/edit' ? '/members/enterprise-a/edit' : path
      await router.push(`${target}?page=2`)
      expect(router.currentRoute.value.path).toBe('/login')
      expect(router.currentRoute.value.query.redirect).toBe(`${target}?page=2`)
    }
  })

  it('preserves an authorized deep link but cannot use it to enter an admin workspace', async () => {
    const { router } = harness(user('ENTERPRISE_ADMIN'), '/policies?q=管网#results')
    await router.push('/auth/callback')
    expect(router.currentRoute.value.path).toBe('/policies')
    expect(router.currentRoute.value.query.q).toBe('管网')
    expect(router.currentRoute.value.hash).toBe('#results')
    await router.push('/login?redirect=/operations&entry=admin')
    expect(router.currentRoute.value.path).toBe('/enterprise')
  })

  it('blocks enterprise cross-company editors before the view mounts', async () => {
    const { router } = harness(user('ENTERPRISE_ADMIN'))
    await router.push('/members/enterprise-b/edit')
    expect(router.currentRoute.value.path).toBe('/enterprise')
    await router.push('/members/enterprise-a/edit')
    expect(router.currentRoute.value.path).toBe('/members/enterprise-a/edit')
    expect(postLoginDestination(router, user('ENTERPRISE_ADMIN'), '/members/enterprise-b/edit')).toBe('/enterprise')
    expect(postLoginDestination(router, user('ENTERPRISE_MEMBER'), '/members/enterprise-a/edit')).toBe('/enterprise')
  })

  it('enforces the role matrix for all workspace pages', async () => {
    for (const role of ROLES) {
      for (const [path, roles] of Object.entries(protectedRouteRoles)) {
        const { router } = harness(user(role))
        const target = path === '/members/edit' ? '/members/enterprise-a/edit' : path
        await router.push(target)
        expect(router.currentRoute.value.path, `${role}: ${target}`).toBe(roles.includes(role) ? target : workspaceForUser(user(role)))
      }
    }
  })

  it.each([
    user('ENTERPRISE_ADMIN', { enterpriseId: null }),
    user('ENTERPRISE_MEMBER', { associationId: null }),
    user('ASSOCIATION_ADMIN', { associationId: null }),
    user('OBSERVER', { associationId: null }),
  ])('shows a non-business binding help page for incomplete verified scope', async (identity) => {
    const { router } = harness(identity, '/members')
    await router.push('/auth/callback')
    expect(router.currentRoute.value.path).toBe('/access-help')
    await router.push('/public')
    expect(router.currentRoute.value.path).toBe('/public')
    await router.push('/members')
    expect(router.currentRoute.value.path).toBe('/access-help')
  })

  it('allows an unscoped system administrator to select context and recovers after binding is fixed', async () => {
    expect(workspaceForUser(user('SYSTEM_ADMIN', { associationId: null }))).toBe('/association')
    const { router, auth } = harness(user('ENTERPRISE_ADMIN', { enterpriseId: null }))
    await router.push('/access-help')
    auth.user.value = user('ENTERPRISE_ADMIN')
    await router.push('/login')
    expect(router.currentRoute.value.path).toBe('/enterprise')
  })

  it.each(['/login', '/auth/callback', '/public', '/access-help', '/missing', '//evil.test', 'https://evil.test'])('does not accept %s as a post-login workspace redirect', (path) => {
    const { router } = harness()
    expect(postLoginDestination(router, user('ENTERPRISE_ADMIN'), path)).toBe('/enterprise')
  })
})

describe('local redirect normalization', () => {
  it.each(['//evil.test', '/\\evil.test', '/%5cevil.test', '/%2fevil.test', '/%0aevil.test', '/%00', '/%', 'https://evil.test', 'javascript:alert(1)', null, ['/', '/operations']])('rejects unsafe redirect %j', (path) => {
    expect(safeLocalPath(path)).toBe('/')
  })
  it('keeps local searches and fragments while normalizing dot segments', () => {
    expect(safeLocalPath('/members?q=gas%20pipe#results')).toBe('/members?q=gas%20pipe#results')
    expect(safeLocalPath('/members/../operations')).toBe('/operations')
  })
})
