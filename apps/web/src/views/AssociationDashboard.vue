<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
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

function displayActivityDetail(detail: string): string {
  const separator = detail.indexOf('：')
  if (separator < 0) return detail
  const label = detail.slice(0, separator + 1)
  const value = detail.slice(separator + 1)
  return `${label}${displayBusinessStatus(value)}`
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
    <PageHeader eyebrow="ASSOCIATION OVERVIEW" title="协会工作台" description="待办优先、状态清晰、全程可追溯的协会工作入口">
      <RouterLink class="secondary-button" to="/members?action=import">导入企业资料</RouterLink>
      <RouterLink class="primary-button" to="/collaborations?create=1">+ 发布协会事项</RouterLink>
    </PageHeader>
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
          <div class="panel-header"><div><h2>行业场景覆盖</h2><p>会员能力在地下管线全生命周期中的分布</p></div><RouterLink class="text-button" to="/ecosystem">查看能力资产 →</RouterLink></div>
          <div class="scene-list">
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
</template>
