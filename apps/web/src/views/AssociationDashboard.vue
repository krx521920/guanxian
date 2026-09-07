<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import NavIcon from '../components/NavIcon.vue'
import { displayStatus } from '../components/status-display'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { displayBusinessStatus, formatDateTime } from './business-form'

const { data, loading, error, load } = useAsyncResource(platformApi.associationDashboard)
onMounted(load)

const loadedAt = ref<Date | null>(null)
watch(data, () => { loadedAt.value = new Date() })
const updatedAtLabel = computed(() => loadedAt.value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(loadedAt.value)
  : '—')

const activityIcons: Record<string, string> = { policy: '策', match: '荐', member: '企', task: '协', collaboration: '协' }
const weakestScene = computed(() => data.value?.sceneDistribution.length
  ? [...data.value.sceneDistribution].sort((left, right) => left.percent - right.percent)[0]
  : null)

function displayActivityDetail(value: string): string {
  return value.replace(/\b(COMPLETED|DRAFT|OPEN|IN_PROGRESS|PUBLISHED)\b/g, (status) => displayStatus(status))
}

function displayActivityTime(value: string): string {
  return value.match(/^\d{4}-\d{2}-\d{2}/)?.[0] || value
}

function overdueDays(dueDate: string | null | undefined): number {
  if (!dueDate) return 0
  const due = new Date(`${dueDate}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.max(0, Math.floor((today.getTime() - due.getTime()) / 86_400_000))
}

interface TodoItem {
  id: string
  kind: 'review' | 'collab' | 'overdue'
  badge: string
  title: string
  meta: string
  overdue: boolean
  to: string
  action: string
}

const todos = computed<TodoItem[]>(() => {
  const list: TodoItem[] = []
  const pendingReview = data.value?.metrics.find((metric) => metric.label === '待审核事项')
  if (pendingReview && pendingReview.value !== '0') {
    list.push({
      id: 'todo-review', kind: 'review', badge: '待审核',
      title: '会员与政策资料待审核',
      meta: `${pendingReview.change} · 请核对后通过或退回`,
      overdue: false, to: '/members', action: '去审核',
    })
  }
  for (const task of data.value?.pendingTasks ?? []) {
    const overdue = overdueDays(task.dueDate) > 0
    list.push({
      id: task.id, kind: overdue ? 'overdue' : 'collab', badge: overdue ? '逾期' : '协作',
      title: task.title,
      meta: `负责人：${task.owner || '未指定'} · 截止 ${task.dueDate || '未设置'} · 下一步：${task.nextAction || '待确定'}`,
      overdue, to: '/collaborations', action: overdue ? '立即处理' : '去处理',
    })
  }
  return list
})

const memberActivities = computed(() => (data.value?.activities ?? []).filter((activity) => activity.type !== 'policy'))
const policyActivities = computed(() => (data.value?.activities ?? []).filter((activity) => activity.type === 'policy'))

const quickLinks = computed(() => {
  const links: Array<{ label: string; to: string; count?: string }> = []
  for (const metric of data.value?.metrics ?? []) {
    if (metric.label === '待审核事项') links.push({ label: '待审核事项', to: '/members', count: metric.value })
    if (metric.label === '待处理事项') links.push({ label: '待处理事项', to: '/collaborations', count: metric.value })
    if (metric.label === '逾期事项') links.push({ label: '逾期事项', to: '/collaborations', count: metric.value })
    if (metric.label === '政策更新') links.push({ label: '政策更新', to: '/policies', count: metric.value })
  }
  return links
})
</script>

<template>
  <div>
    <section class="workspace-resources" aria-labelledby="workspace-resources-title">
      <div class="workspace-resource-heading">
        <div><span class="workspace-section-label">YOUR WORKSPACE</span><h2 id="workspace-resources-title">从这里，继续您的工作</h2></div>
        <RouterLink to="/members?action=import">导入企业资料 <span aria-hidden="true">↗</span></RouterLink>
      </div>
      <nav class="workspace-resource-grid" aria-label="常用业务入口">
        <RouterLink to="/members" class="workspace-resource-card resource-members">
          <div class="resource-art"><span class="resource-code">01 / MEMBERS</span><NavIcon name="enterprise" /><span class="resource-arrow" aria-hidden="true">↗</span></div>
          <strong>会员企业</strong><p>查看企业档案，发现会员能力</p>
        </RouterLink>
        <RouterLink to="/policies" class="workspace-resource-card resource-policies">
          <div class="resource-art"><span class="resource-code">02 / KNOWLEDGE</span><NavIcon name="policy" /><span class="resource-arrow" aria-hidden="true">↗</span></div>
          <strong>政策与标准</strong><p>查阅政策原文，核对参考依据</p>
        </RouterLink>
        <RouterLink to="/ecosystem" class="workspace-resource-card resource-ecosystem">
          <div class="resource-art"><span class="resource-code">03 / ECOSYSTEM</span><NavIcon name="ecosystem" /><span class="resource-arrow" aria-hidden="true">↗</span></div>
          <strong>产品与需求</strong><p>连接企业供需，了解合作机会</p>
        </RouterLink>
        <RouterLink to="/collaborations" class="workspace-resource-card resource-tasks">
          <div class="resource-art"><span class="resource-code">04 / COLLABORATE</span><NavIcon name="task" /><span class="resource-arrow" aria-hidden="true">↗</span></div>
          <strong>协作事项</strong><p>查看待办与进展，推进协会工作</p>
        </RouterLink>
      </nav>
    </section>

    <details class="workspace-overview">
      <summary><span><strong>业务概览</strong><small>待办、会员动态与协作进展</small></span><span class="overview-chevron" aria-hidden="true">⌄</span></summary>
      <div class="workspace-overview-body">
        <div class="workspace-overview-actions"><RouterLink class="secondary-button small" to="/collaborations?create=1">发布协会事项</RouterLink><button class="secondary-button small" type="button" @click="load">刷新概览</button></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="metrics-grid" aria-label="关键指标">
        <MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['审', '办', '逾', '策'][index]" />
      </section>

      <nav class="quick-links" aria-label="快捷入口">
        <RouterLink v-for="link in quickLinks" :key="link.label" class="quick-link" :to="link.to">
          {{ link.label }}<b v-if="link.count">{{ link.count }}</b> →
        </RouterLink>
      </nav>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header">
            <div><h2>待办事项</h2><p>需要协会处理或跟进的事项，按紧急程度排列</p></div>
            <RouterLink class="text-button" to="/collaborations">全部事项 →</RouterLink>
          </div>
          <div v-if="todos.length" class="todo-list">
            <div v-for="todo in todos" :key="todo.id" class="todo-item" :class="{ 'is-overdue': todo.overdue }">
              <span class="todo-type" :class="todo.kind">{{ todo.badge }}</span>
              <div class="todo-item-body">
                <strong>{{ todo.title }}</strong>
                <small :class="{ overdue: todo.overdue }">{{ todo.meta }}</small>
              </div>
              <RouterLink class="todo-action" :class="todo.kind" :to="todo.to">{{ todo.action }}</RouterLink>
            </div>
          </div>
          <div v-else class="empty-business-state"><b>当前没有待办事项</b><span>新的审核、推进和逾期提醒会出现在这里。</span><RouterLink class="secondary-button small" to="/collaborations">查看协作事项</RouterLink></div>
          <div class="data-source panel-source"><span>数据来源：<b>平台业务数据库</b></span><span>更新时间：<b>{{ updatedAtLabel }}</b></span><span>可见范围：<b>本协会</b></span></div>
        </article>

        <article class="panel">
          <div class="panel-header"><div><h2>会员动态</h2><p>会员企业与供需匹配的最新变化</p></div><button class="icon-button" aria-label="刷新工作台" @click="load">↻</button></div>
          <div class="activity-list">
            <div v-for="activity in memberActivities" :key="activity.id" class="activity-item">
              <span class="activity-icon" :class="activity.type">{{ activityIcons[activity.type] }}</span>
              <div><strong>{{ activity.title }}</strong><p>{{ displayActivityDetail(activity.detail) }}</p><small>{{ formatDateTime(activity.time) }}</small></div>
            </div>
            <div v-if="!memberActivities.length" class="empty-business-state"><span>暂无会员动态。</span></div>
          </div>
          <div class="data-source panel-source"><span>数据来源：<b>平台业务数据库</b></span><span>更新时间：<b>{{ updatedAtLabel }}</b></span></div>
        </article>
      </section>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>政策提醒</h2><p>近期发布、征求意见的政策</p></div><RouterLink class="text-button" to="/policies">政策中心 →</RouterLink></div>
          <div class="activity-list">
            <div v-for="activity in policyActivities" :key="activity.id" class="activity-item">
              <span class="activity-icon policy">{{ activityIcons[activity.type] }}</span>
              <div><strong>{{ activity.title }}</strong><p>{{ displayActivityDetail(activity.detail) }}</p><small>{{ formatDateTime(activity.time) }}</small></div>
            </div>
            <div v-if="!policyActivities.length" class="empty-business-state"><span>暂无政策提醒。</span><RouterLink class="secondary-button small" to="/policies">查看政策中心</RouterLink></div>
          </div>
          <div class="data-source panel-source"><span>数据来源：<b>政策标准中心</b></span><span>更新时间：<b>{{ updatedAtLabel }}</b></span></div>
        </article>

        <article class="panel task-panel">
          <div class="panel-header"><div><h2>协作进展</h2><p>需要协会协调或跟进的重点事项</p></div><RouterLink class="text-button" to="/collaborations">全部事项 →</RouterLink></div>
          <div class="data-table-wrap">
            <table class="data-table">
              <thead><tr><th>协作事项</th><th>参与方</th><th>负责人</th><th>阶段</th><th>下一步</th><th>截止日期</th></tr></thead>
              <tbody>
                <tr v-for="task in data.pendingTasks" :key="task.id">
                  <td><strong>{{ task.title }}</strong></td>
                  <td>{{ task.participants.join(' × ') }}</td>
                  <td>{{ task.owner }}</td>
                  <td><StatusBadge :value="displayBusinessStatus(task.stage)" /></td>
                  <td>{{ task.nextAction }}</td>
                  <td :class="{ 'danger-text': overdueDays(task.dueDate) > 0 }">{{ task.dueDate }}<span v-if="overdueDays(task.dueDate) > 0">（逾期 {{ overdueDays(task.dueDate) }} 天）</span></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="data-source panel-source"><span>数据来源：<b>协作事项库</b></span><span>更新时间：<b>{{ updatedAtLabel }}</b></span><span>可见范围：<b>本协会</b></span></div>
        </article>
      </section>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>行业场景覆盖</h2><p>会员能力在地下管线全生命周期中的分布</p></div><RouterLink class="text-button" to="/ecosystem">查看生态概览 →</RouterLink></div>
          <div v-if="data.sceneDistribution.length" class="scene-list">
            <div v-for="scene in data.sceneDistribution" :key="scene.name" class="scene-row">
              <span>{{ scene.name }}</span><div class="progress-track"><i :style="{ width: `${scene.percent}%` }" /></div><strong>{{ scene.count }} 家</strong>
            </div>
          </div>
          <div class="insight-callout"><span>数据</span><p v-if="weakestScene"><b>数据提示</b>当前覆盖最低的已建档场景为“{{ weakestScene.name }}”（{{ weakestScene.count }} 条能力/需求记录），建议结合真实业务进一步核实。</p><p v-else><b>数据提示</b>暂无场景资产，请先组织企业建档。</p></div>
        </article>

        <article class="panel">
          <div class="panel-header"><div><h2>最近操作</h2><p>平台操作留痕，支持追溯核查</p></div><RouterLink class="text-button" to="/collaborations">审计记录 →</RouterLink></div>
          <div class="audit-list">
            <div v-for="activity in data.activities" :key="activity.id" class="audit-row">
              <time>{{ formatDateTime(activity.time) }}</time>
              <span class="todo-type" :class="activity.type === 'policy' ? 'review' : 'collab'">{{ activityIcons[activity.type] }}</span>
              <div><strong>{{ activity.title }}</strong><p>{{ activity.detail }}</p></div>
              <span class="table-muted">留痕</span>
            </div>
          </div>
          <p class="audit-note">操作记录由平台统一留存、不可修改；完整审计日志可向系统管理员申请查询。</p>
        </article>
      </section>
    </template>
      </div>
    </details>
  </div>
</template>

<style scoped>
.workspace-resources, .workspace-overview { max-width: 1000px; margin: 0 auto; }
.workspace-resource-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.workspace-section-label { display: block; color: var(--muted); font-size: 9px; font-weight: 650; letter-spacing: .14em; margin-bottom: 6px; }
.workspace-resource-heading h2 { margin: 0; color: var(--ink); font-size: 17px; font-weight: 600; }
.workspace-resource-heading > a { color: var(--muted); font-size: 12px; }
.workspace-resource-heading > a:hover { color: var(--primary); }
.workspace-resource-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; }
.workspace-resource-card { min-width: 0; color: var(--ink); }
.resource-art { position: relative; height: 146px; padding: 15px; border: 1px solid color-mix(in srgb, var(--line) 65%, transparent); border-radius: 17px; background: var(--art-background); color: var(--art-color); overflow: hidden; transition: transform .18s ease, box-shadow .18s ease; }
.resource-art::before { content: ''; position: absolute; width: 140px; height: 140px; border: 1px solid currentColor; border-radius: 50%; opacity: .12; right: -35px; top: -35px; }
.resource-art::after { content: ''; position: absolute; width: 95px; height: 95px; border: 1px solid currentColor; border-radius: 50%; opacity: .12; right: -12px; top: -12px; }
.resource-code { display: block; position: relative; z-index: 1; font-size: 8px; letter-spacing: .12em; opacity: .78; }
.resource-art :deep(svg) { position: absolute; width: 54px; height: 54px; bottom: 20px; left: 20px; stroke-width: 1.05; }
.resource-arrow { position: absolute; right: 16px; bottom: 14px; font-size: 19px; opacity: .7; }
.resource-members { --art-background: color-mix(in srgb, var(--panel) 87%, #347769); --art-color: #36746b; }
.resource-policies { --art-background: color-mix(in srgb, var(--panel) 87%, #bb905f); --art-color: #986e3d; }
.resource-ecosystem { --art-background: color-mix(in srgb, var(--panel) 87%, #7183a1); --art-color: #657c9d; }
.resource-tasks { --art-background: color-mix(in srgb, var(--panel) 87%, #a28c98); --art-color: #916e85; }
.workspace-resource-card > strong { display: block; margin: 13px 2px 6px; font-size: 14px; font-weight: 600; }
.workspace-resource-card > p { margin: 0 2px; font-size: 11px; color: var(--muted); line-height: 1.7; }
.workspace-resource-card:hover .resource-art { transform: translateY(-3px); box-shadow: 0 8px 22px rgba(25, 43, 42, .07); }
.workspace-resource-card:focus-visible { outline: 3px solid var(--primary); outline-offset: 5px; border-radius: 17px; }
.workspace-overview { margin-top: 32px; border-top: 1px solid var(--line); }
.workspace-overview > summary { list-style: none; display: flex; justify-content: space-between; align-items: center; padding: 20px 2px; cursor: pointer; color: var(--ink); }
.workspace-overview > summary::-webkit-details-marker { display: none; }
.workspace-overview > summary strong { font-size: 14px; font-weight: 600; }
.workspace-overview > summary small { color: var(--muted); font-size: 11px; margin-left: 12px; }
.workspace-overview > summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.overview-chevron { font-size: 18px; color: var(--muted); transition: transform .18s ease; }
.workspace-overview[open] .overview-chevron { transform: rotate(180deg); }
.workspace-overview-body { padding: 0 0 20px; }
.workspace-overview-actions { display: flex; justify-content: flex-end; gap: 10px; margin-bottom: 18px; }
@media (max-width: 800px) { .workspace-resource-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 14px; } .resource-art { height: 132px; } }
@media (max-width: 480px) { .workspace-resource-heading h2 { font-size: 15px; } .workspace-resource-heading > a { font-size: 10px; white-space: nowrap; } .workspace-resource-grid { gap: 18px 12px; } .resource-art { height: 118px; border-radius: 14px; padding: 12px; } .resource-code { font-size: 7px; } .resource-art :deep(svg) { width: 44px; height: 44px; bottom: 16px; left: 16px; } .workspace-resource-card > strong { font-size: 13px; } .workspace-resource-card > p { font-size: 10px; } .workspace-overview > summary small { display: block; margin: 6px 0 0; } }
@media (prefers-reduced-motion: reduce) { .resource-art, .overview-chevron { transition: none; } }
</style>
