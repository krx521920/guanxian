<script setup lang="ts">
import { computed, onMounted } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { displayBusinessStatus, formatDateTime } from './business-form'

const { data, loading, error, load } = useAsyncResource(platformApi.associationDashboard)
onMounted(load)

const activityIcons = { policy: '规', match: '荐', member: '企', task: '协', collaboration: '协' }
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
</script>

<template>
  <div>
    <PageHeader eyebrow="ASSOCIATION OVERVIEW" title="协会工作台" description="掌握会员动态、行业资源与生态协作全局">
      <RouterLink class="secondary-button" to="/members?action=import">导入企业资料</RouterLink>
      <RouterLink class="primary-button" to="/collaborations?create=1">+ 发布协会事项</RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="metrics-grid">
        <MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['企', '档', '联', '待'][index]" />
      </section>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>行业场景覆盖</h2><p>会员能力在地下管线全生命周期中的分布</p></div><RouterLink class="text-button" to="/ecosystem">查看能力资产 →</RouterLink></div>
          <div class="scene-list">
            <div v-for="scene in data.sceneDistribution" :key="scene.name" class="scene-row">
              <span>{{ scene.name }}</span><div class="progress-track"><i :style="{ width: `${scene.percent}%` }" /></div><strong>{{ scene.count }} 家</strong>
            </div>
          </div>
          <div class="insight-callout"><span>DATA</span><p v-if="weakestScene"><b>数据提示</b>当前覆盖最低的已建档场景为“{{ weakestScene.name }}”（{{ weakestScene.count }} 条能力/需求记录），建议结合真实业务进一步核实。</p><p v-else><b>数据提示</b>暂无场景资产，请先组织企业建档。</p></div>
        </article>

        <article class="panel">
          <div class="panel-header"><div><h2>最新动态</h2><p>政策、会员和协作变化</p></div><button class="icon-button" aria-label="刷新工作台" @click="load">↻</button></div>
          <div class="activity-list">
            <div v-for="activity in data.activities" :key="activity.id" class="activity-item">
              <span class="activity-icon" :class="activity.type">{{ activityIcons[activity.type] }}</span>
              <div><strong>{{ activity.title }}</strong><p>{{ displayActivityDetail(activity.detail) }}</p><small>{{ formatDateTime(activity.time) }}</small></div>
            </div>
          </div>
        </article>
      </section>

      <section class="panel task-panel">
        <div class="panel-header"><div><h2>待推进协作</h2><p>需要协会协调或跟进的重点事项</p></div><RouterLink class="text-button" to="/collaborations">全部事项 →</RouterLink></div>
        <div class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>协作事项</th><th>参与方</th><th>负责人</th><th>阶段</th><th>下一步</th><th>截止日期</th></tr></thead>
            <tbody><tr v-for="task in data.pendingTasks" :key="task.id"><td><strong>{{ task.title }}</strong></td><td>{{ task.participants.join(' × ') }}</td><td>{{ task.owner }}</td><td><StatusBadge :value="displayBusinessStatus(task.stage)" /></td><td>{{ task.nextAction }}</td><td>{{ task.dueDate }}</td></tr></tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
