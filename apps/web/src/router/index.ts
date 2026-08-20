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
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', redirect: '/association' },
        { path: 'association', component: () => import('../views/AssociationDashboard.vue'), meta: { title: '协会工作台', roles: protectedRouteRoles['/association'] } },
        { path: 'enterprise', component: () => import('../views/EnterpriseDashboard.vue'), meta: { title: '企业工作台', roles: protectedRouteRoles['/enterprise'] } },
        { path: 'members', component: () => import('../views/MembersView.vue'), meta: { title: '会员企业', roles: protectedRouteRoles['/members'] } },
        { path: 'policies', component: () => import('../views/PoliciesView.vue'), meta: { title: '政策标准', roles: protectedRouteRoles['/policies'] } },
        { path: 'matching', component: () => import('../views/MatchingView.vue'), meta: { title: '生态匹配', roles: protectedRouteRoles['/matching'] } },
        { path: 'collaborations', component: () => import('../views/CollaborationsView.vue'), meta: { title: '协作事项', roles: protectedRouteRoles['/collaborations'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  const { user } = useAuth()
  if (to.path === '/login') return user.value ? defaultRouteForRole(user.value.role) : true
  if (!user.value) return { path: '/login', query: { redirect: to.fullPath } }
  if (!hasAnyRole(user.value.role, to.meta.roles)) return defaultRouteForRole(user.value.role)
  if (to.path === '/') return defaultRouteForRole(user.value.role)
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '工作台'} · 管线智联`
})

export default router
