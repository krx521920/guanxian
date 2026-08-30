<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Building2, ClipboardCheck, GitCompareArrows, ScrollText } from '@lucide/vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { displayStatus } from '../components/status-display'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { associationDashboard as associationSample } from '../mocks/data'
import { platformApi } from '../services/platform-api'

const { data, loading, error, load } = useAsyncResource(platformApi.associationDashboard)
onMounted(load)

const sceneDistribution = computed(() => data.value?.sceneDistribution.length
  ? data.value.sceneDistribution
  : associationSample.sceneDistribution)

const activityIcons = { policy: ScrollText, match: GitCompareArrows, member: Building2, task: ClipboardCheck }

function displayActivityDetail(value: string): string {
  return value.replace(/\b(COMPLETED|DRAFT|OPEN|IN_PROGRESS|PUBLISHED)\b/g, (status) => displayStatus(status))
}

function displayActivityTime(value: string): string {
  return value.match(/^\d{4}-\d{2}-\d{2}/)?.[0] || value
}
</script>

<template>
  <div class="association-page">
    <PageHeader title="协会工作台" description="掌握会员动态、行业资源与生态协作全局">
      <button class="secondary-button">导入企业资料</button>
      <button class="primary-button">+ 发布协会事项</button>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="metrics-grid">
        <MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['企', '档', '联', '待'][index]" />
      </section>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>行业场景覆盖</h2><p>会员能力在地下管线全生命周期中的分布</p></div><button class="text-button">查看能力图谱 →</button></div>
          <div class="scene-list">
            <div v-for="scene in sceneDistribution" :key="scene.name" class="scene-row">
              <span>{{ scene.name }}</span><div class="progress-track"><i :style="{ width: `${scene.percent}%` }" /></div><strong>{{ scene.count }} 家</strong>
            </div>
          </div>
          <div class="insight-callout"><span>AI</span><p><b>能力缺口提示</b>应急处置场景覆盖相对不足，建议定向发展检测设备与抢险服务类会员。</p></div>
        </article>

        <article class="panel">
          <div class="panel-header"><div><h2>最新动态</h2><p>政策、会员和协作变化</p></div><button class="icon-button">•••</button></div>
          <div class="activity-list">
            <div v-for="activity in data.activities" :key="activity.id" class="activity-item">
              <span class="activity-icon" :class="activity.type"><component :is="activityIcons[activity.type]" aria-hidden="true" /></span>
              <div><strong>{{ activity.title }}</strong><p>{{ displayActivityDetail(activity.detail) }}</p><small>{{ displayActivityTime(activity.time) }}</small></div>
            </div>
          </div>
        </article>
      </section>

      <section class="panel task-panel">
        <div class="panel-header"><div><h2>待推进协作</h2><p>需要协会协调或跟进的重点事项</p></div><RouterLink class="text-button" to="/collaborations">全部事项 →</RouterLink></div>
        <div class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>协作事项</th><th>参与方</th><th>负责人</th><th>阶段</th><th>下一步</th><th>截止日期</th></tr></thead>
            <tbody><tr v-for="task in data.pendingTasks" :key="task.id"><td><strong>{{ task.title }}</strong></td><td>{{ task.participants.join(' × ') }}</td><td>{{ task.owner }}</td><td><StatusBadge :value="task.stage" /></td><td>{{ task.nextAction }}</td><td>{{ task.dueDate }}</td></tr></tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
