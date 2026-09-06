import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../services/auth'
import type { UserRole } from '../types/domain'
import { protectedRouteRoles } from './permissions'
import { installAccessGuard } from './access'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    roles?: readonly UserRole[]
    public?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', alias: '/', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '统一入口' } },
    { path: '/public', name: 'public-portal', component: () => import('../views/PublicPortalView.vue'), meta: { title: '公开平台', public: true } },
    { path: '/access-help', name: 'access-help', component: () => import('../views/AccountAccessView.vue'), meta: { title: '账号访问说明' } },
    { path: '/join', name: 'enterprise-join', component: () => import('../views/EnterpriseJoinView.vue'), meta: { title: '企业负责人绑定' } },
    { path: '/auth/callback', name: 'auth-callback', component: () => import('../views/OidcCallbackView.vue'), meta: { title: '身份验证' } },
    {
      path: '/workspace',
      component: () => import('../layouts/AppShell.vue'),
      children: [
        { path: '/association', component: () => import('../views/AssociationDashboard.vue'), meta: { title: '协会工作台', roles: protectedRouteRoles['/association'] } },
        { path: '/enterprise', component: () => import('../views/EnterpriseDashboard.vue'), meta: { title: '企业工作台', roles: protectedRouteRoles['/enterprise'] } },
        { path: '/enterprise/profile', component: () => import('../views/MyEnterpriseView.vue'), meta: { title: '我的企业', roles: protectedRouteRoles['/enterprise/profile'] } },
        { path: '/operations/invitations', component: () => import('../views/EnterpriseInvitationsView.vue'), meta: { title: '企业负责人邀请', roles: protectedRouteRoles['/operations/invitations'] } },
        { path: '/members', component: () => import('../views/MembersView.vue'), meta: { title: '会员企业', roles: protectedRouteRoles['/members'] } },
        { path: '/members/new', component: () => import('../views/MemberCreateView.vue'), meta: { title: '新增会员企业', roles: protectedRouteRoles['/members/new'] } },
        { path: '/members/:id/edit', name: 'member-edit', component: () => import('../views/MemberEditView.vue'), meta: { title: '编辑会员企业', roles: protectedRouteRoles['/members/edit'] } },
        { path: '/policies', component: () => import('../views/PoliciesView.vue'), meta: { title: '政策标准', roles: protectedRouteRoles['/policies'] } },
        { path: '/ecosystem/overview', component: () => import('../views/EcosystemOverviewView.vue'), meta: { title: '生态全景', roles: protectedRouteRoles['/ecosystem/overview'] } },
        { path: '/ecosystem', component: () => import('../views/EcosystemCatalogView.vue'), meta: { title: '产品与需求', roles: protectedRouteRoles['/ecosystem'] } },
        { path: '/matching', component: () => import('../views/MatchingView.vue'), meta: { title: '生态匹配', roles: protectedRouteRoles['/matching'] } },
        { path: '/collaborations', component: () => import('../views/CollaborationsView.vue'), meta: { title: '协作事项', roles: protectedRouteRoles['/collaborations'] } },
        { path: '/attachments', component: () => import('../views/AttachmentCenterView.vue'), meta: { title: '资料附件', roles: protectedRouteRoles['/attachments'] } },
        { path: '/federation', component: () => import('../views/FederationView.vue'), meta: { title: '友好协会', roles: protectedRouteRoles['/federation'] } },
        { path: '/operations', component: () => import('../views/OperationsView.vue'), meta: { title: '审计与账号', roles: protectedRouteRoles['/operations'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

installAccessGuard(router, useAuth())

router.afterEach((to) => {
  document.title = `${to.meta.title || '工作台'} · 管线智联`
})

export default router
