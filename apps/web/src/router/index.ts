import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '../layouts/AppShell.vue'
import { defaultRouteForRole, hasAnyRole } from '../config/roles'
import { useAuth } from '../services/auth'
import type { UserRole } from '../types/domain'
import { protectedRouteRoles } from './permissions'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    roles?: readonly UserRole[]
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/auth/callback', name: 'auth-callback', component: () => import('../views/OidcCallbackView.vue'), meta: { title: '身份验证' } },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/association' },
        { path: 'association', component: () => import('../views/AssociationDashboard.vue'), meta: { title: '协会工作台', roles: protectedRouteRoles['/association'] } },
        { path: 'enterprise', component: () => import('../views/EnterpriseDashboard.vue'), meta: { title: '企业工作台', roles: protectedRouteRoles['/enterprise'] } },
        { path: 'members', component: () => import('../views/MembersView.vue'), meta: { title: '会员企业', roles: protectedRouteRoles['/members'] } },
        { path: 'members/new', component: () => import('../views/MemberCreateView.vue'), meta: { title: '新增会员企业', roles: protectedRouteRoles['/members/new'] } },
        { path: 'members/:id/edit', component: () => import('../views/MemberEditView.vue'), meta: { title: '编辑会员企业', roles: protectedRouteRoles['/members/edit'] } },
        { path: 'policies', component: () => import('../views/PoliciesView.vue'), meta: { title: '政策标准', roles: protectedRouteRoles['/policies'] } },
        { path: 'matching', component: () => import('../views/MatchingView.vue'), meta: { title: '生态匹配', roles: protectedRouteRoles['/matching'] } },
        { path: 'collaborations', component: () => import('../views/CollaborationsView.vue'), meta: { title: '协作事项', roles: protectedRouteRoles['/collaborations'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuth()
  await auth.initialize()

  if (to.path === '/auth/callback') {
    if (!auth.user.value) return '/login'
    return auth.takePostLoginRoute() || defaultRouteForRole(auth.user.value.role)
  }
  if (to.path === '/login') return auth.user.value ? defaultRouteForRole(auth.user.value.role) : true
  if (!auth.user.value) return { path: '/login', query: { redirect: to.fullPath } }
  if (!hasAnyRole(auth.user.value.role, to.meta.roles)) return defaultRouteForRole(auth.user.value.role)
  if (to.path === '/') return defaultRouteForRole(auth.user.value.role)
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '工作台'} · 管线智联`
})

export default router
