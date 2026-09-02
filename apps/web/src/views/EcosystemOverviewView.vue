<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import { roleLabels } from '../config/roles'
import { useAsyncResource } from '../composables/useAsyncResource'
import { protectedRouteRoles } from '../router/permissions'
import { useAuth } from '../services/auth'
import { ROLES, type UserRole } from '../types/domain'
import {
  catalogSampleDescription,
  hasOverviewData,
  loadEcosystemOverview,
  summarizeScenarios,
} from './ecosystem-overview'

const auth = useAuth()
const permissionOpen = ref(false)
const permissionDialog = ref<HTMLElement | null>(null)
const { data: snapshot, loading, error, load } = useAsyncResource(loadEcosystemOverview)

const scenarioSummary = computed(() => snapshot.value ? summarizeScenarios(snapshot.value) : [])
const hasEcosystemData = computed(() => snapshot.value ? hasOverviewData(snapshot.value) : false)
const sampleDescription = computed(() => snapshot.value ? catalogSampleDescription(snapshot.value) : '')

const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  ACTIVE: '已发布',
  OPEN: '开放中',
  CLOSED: '已关闭',
  DISABLED: '已停用',
}

const workspaceRoutes = [
  { to: '/members', label: '会员企业', description: '查看当前身份被授权访问的企业资料。' },
  { to: '/policies', label: '政策标准', description: '查询平台已入库并处于可见范围内的政策资料。' },
  { to: '/matching', label: '生态匹配', description: '查看真实匹配记录及其后续协作状态。' },
  { to: '/collaborations', label: '协作事项', description: '跟进已建立的洽谈与协作任务。' },
] as const

const currentRole = computed<UserRole | null>(() => auth.user.value?.role ?? null)
const currentRoleLabel = computed(() => currentRole.value ? roleLabels[currentRole.value] : '未识别身份')
const availableWorkspaces = computed(() => {
  const role = currentRole.value
  if (!role) return []
  return workspaceRoutes.filter((item) => protectedRouteRoles[item.to]?.includes(role))
})

function canAccess(role: UserRole, path: string): boolean {
  return Boolean(protectedRouteRoles[path]?.includes(role))
}

function statusLabel(value: string): string {
  return statusLabels[value] ?? value
}

function enterpriseLabel(value: string | null): string {
  return value?.trim() || '企业名称未授权展示'
}

async function openPermissions() {
  permissionOpen.value = true
  await nextTick()
  permissionDialog.value?.focus()
}

onMounted(load)
</script>

<template>
  <div class="ecosystem-page">
    <PageHeader
      title="产业生态数据概览"
      description="所有数量均来自当前账号可见的真实业务数据；数据范围由服务端按身份和协会关系校验"
    >
      <RouterLink class="primary-button ecosystem-link-button" to="/matching">进入生态匹配 →</RouterLink>
    </PageHeader>

    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />

    <template v-else-if="snapshot">
      <section class="metrics-grid" aria-label="生态数据摘要">
        <article class="metric-card tone-info">
          <div class="metric-top"><span class="metric-label">当前可见会员</span><span class="metric-symbol">企</span></div>
          <strong>{{ snapshot.members.length }}</strong>
          <p>按当前账号的数据权限返回，不代表全平台总量</p>
        </article>
        <article class="metric-card tone-success">
          <div class="metric-top"><span class="metric-label">产品与服务档案</span><span class="metric-symbol">供</span></div>
          <strong>{{ snapshot.offerings.total }}</strong>
          <p>来自产品与服务目录接口的可见记录总数</p>
        </article>
        <article class="metric-card tone-warning">
          <div class="metric-top"><span class="metric-label">合作需求档案</span><span class="metric-symbol">需</span></div>
          <strong>{{ snapshot.demands.total }}</strong>
          <p>包含当前身份可查看的各状态需求</p>
        </article>
        <article class="metric-card">
          <div class="metric-top"><span class="metric-label">匹配记录</span><span class="metric-symbol">合</span></div>
          <strong>{{ snapshot.matches.length }}</strong>
          <p>仅统计服务端授权返回的匹配记录</p>
        </article>
      </section>

      <section v-if="!hasEcosystemData" class="resource-error panel" aria-live="polite">
        <span class="resource-error-icon" aria-hidden="true">○</span>
        <h2>当前范围暂无生态数据</h2>
        <p>请先维护企业资料、产品服务或合作需求；平台不会用演示数据填充空状态。</p>
        <RouterLink class="primary-button" to="/members">查看会员企业</RouterLink>
      </section>

      <template v-else>
        <section class="content-grid dashboard-main-grid">
          <article class="panel">
            <div class="panel-header">
              <div><h2>可见档案的场景分布</h2><p>{{ sampleDescription }}</p></div>
            </div>
            <div v-if="scenarioSummary.length" class="scene-list">
              <div v-for="scenario in scenarioSummary" :key="scenario.name" class="scene-row">
                <span>{{ scenario.name }}</span>
                <div class="progress-track"><i :style="{ width: `${scenario.percent}%` }" /></div>
                <strong>{{ scenario.count }} 条</strong>
              </div>
            </div>
            <div v-else class="resource-error">
              <h2>暂无场景标签</h2>
              <p>现有产品、服务和需求尚未填写可统计的场景。</p>
            </div>
          </article>

          <aside class="panel">
            <div class="panel-header"><div><h2>数据口径</h2><p>避免把可见数据误解为全平台即时统计</p></div></div>
            <div class="scene-list">
              <div class="activity-item"><span class="activity-icon">1</span><div><strong>权限范围</strong><p>当前身份：{{ currentRoleLabel }}。接口会继续校验企业、协会和跨协会授权。</p></div></div>
              <div class="activity-item"><span class="activity-icon">2</span><div><strong>更新时间</strong><p>页面加载及手动重试时读取服务端，不宣称推送式即时更新。</p></div></div>
              <div class="activity-item"><span class="activity-icon">3</span><div><strong>智能能力</strong><p>本页只汇总业务数据；未调用模型的内容不会标记为智能分析。</p></div></div>
            </div>
          </aside>
        </section>

        <section class="ecosystem-section-heading">
          <div><h2>最新可见供需档案</h2><p>以下内容来自真实产品、服务和需求目录</p></div>
          <RouterLink class="secondary-button ecosystem-link-button" to="/matching">查看匹配记录</RouterLink>
        </section>
        <section class="tier-grid">
          <article class="tier-card panel">
            <div class="tier-top"><span>供</span><em>{{ snapshot.offerings.total }} 条</em></div>
            <small>PRODUCTS &amp; SERVICES</small><h3>产品与服务</h3>
            <ul v-if="snapshot.offerings.items.length">
              <li v-for="item in snapshot.offerings.items.slice(0, 4)" :key="item.id">
                <span>✓</span>{{ item.name }} · {{ enterpriseLabel(item.enterpriseName) }} · {{ statusLabel(item.status) }}
              </li>
            </ul>
            <p v-else>当前权限范围内暂无产品或服务档案。</p>
            <RouterLink class="secondary-button" to="/members">查看相关企业</RouterLink>
          </article>
          <article class="tier-card panel">
            <div class="tier-top"><span>需</span><em>{{ snapshot.demands.total }} 条</em></div>
            <small>COOPERATION DEMANDS</small><h3>合作需求</h3>
            <ul v-if="snapshot.demands.items.length">
              <li v-for="item in snapshot.demands.items.slice(0, 4)" :key="item.id">
                <span>✓</span>{{ item.title }} · {{ enterpriseLabel(item.enterpriseName) }} · {{ statusLabel(item.status) }}
              </li>
            </ul>
            <p v-else>当前权限范围内暂无合作需求档案。</p>
            <RouterLink class="primary-button" to="/matching">进入匹配工作台</RouterLink>
          </article>
        </section>
      </template>

      <section class="ecosystem-section-heading">
        <div><h2>当前账号可用入口</h2><p>依据现有路由角色配置展示；最终数据权限由服务端决定</p></div>
        <button class="secondary-button" type="button" @click="openPermissions">查看权限说明</button>
      </section>
      <section class="tier-grid">
        <article v-for="workspace in availableWorkspaces" :key="workspace.to" class="tier-card panel">
          <div class="tier-top"><span>→</span><em>{{ currentRoleLabel }}</em></div>
          <small>AVAILABLE WORKSPACE</small><h3>{{ workspace.label }}</h3>
          <p>{{ workspace.description }}</p>
          <RouterLink class="secondary-button" :to="workspace.to">打开{{ workspace.label }}</RouterLink>
        </article>
      </section>
    </template>

    <Teleport to="body">
      <div v-if="permissionOpen" class="dialog-backdrop" @click.self="permissionOpen = false" @keydown.esc="permissionOpen = false">
        <section ref="permissionDialog" class="member-create-dialog permission-dialog" role="dialog" aria-modal="true" aria-labelledby="permission-title" tabindex="-1">
          <header class="dialog-header">
            <div><h2 id="permission-title">页面入口权限说明</h2><p>前端入口与服务端数据权限是两道独立校验</p></div>
            <button class="dialog-close" type="button" aria-label="关闭权限说明" @click="permissionOpen = false">×</button>
          </header>
          <div class="dialog-body permission-dialog-body">
            <p>此表直接读取前端路由角色配置，仅代表能否进入页面。服务端仍会逐请求校验角色、企业归属、协会关系和授权期限。</p>
            <div class="data-table-wrap">
              <table class="data-table">
                <thead><tr><th>身份</th><th v-for="workspace in workspaceRoutes" :key="workspace.to">{{ workspace.label }}</th></tr></thead>
                <tbody>
                  <tr v-for="role in ROLES" :key="role">
                    <td><strong>{{ roleLabels[role] }}</strong><small v-if="role === currentRole">（当前）</small></td>
                    <td v-for="workspace in workspaceRoutes" :key="workspace.to">{{ canAccess(role, workspace.to) ? '可进入' : '不可进入' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="form-actions"><button class="primary-button" type="button" @click="permissionOpen = false">知道了</button></div>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.permission-dialog { width: min(900px, calc(100vw - 64px)); }
.permission-dialog-body { padding: 24px; }
.permission-dialog-body > p { margin-bottom: 20px; color: var(--muted); font-size: 12px; line-height: 1.7; }
.permission-dialog-body .form-actions { position: static; margin: 20px -24px -24px; justify-content: flex-end; }
@media (max-width: 560px) {
  .permission-dialog { width: 100%; }
  .permission-dialog-body { padding: 18px 16px; }
  .permission-dialog-body .form-actions { margin: 18px -16px -18px; }
}
</style>
