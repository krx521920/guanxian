<script setup lang="ts">
import { onMounted } from 'vue'
import { Building2, ClipboardCheck, GitCompareArrows, Plus, ScrollText } from '@lucide/vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { displayStatus } from '../components/status-display'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'

const { data, loading, error, load } = useAsyncResource(platformApi.associationDashboard)
onMounted(load)

const activityIcons = {
  policy: ScrollText,
  match: GitCompareArrows,
  member: Building2,
  task: ClipboardCheck,
  collaboration: ClipboardCheck,
}

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
      <RouterLink class="secondary-button" to="/members">采集企业资料</RouterLink>
      <RouterLink class="primary-button icon-label-button" to="/collaborations"><Plus aria-hidden="true" /><span>查看协作事项</span></RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="metrics-grid">
        <MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['企', '档', '联', '待'][index]" />
      </section>

      <section class="content-grid dashboard-main-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>行业场景覆盖</h2><p>会员能力在地下管线全生命周期中的分布</p></div><RouterLink class="text-button" to="/ecosystem">查看生态概览 →</RouterLink></div>
          <div v-if="data.sceneDistribution.length" class="scene-list">
            <div v-for="scene in data.sceneDistribution" :key="scene.name" class="scene-row">
              <span>{{ scene.name }}</span><div class="progress-track"><i :style="{ width: `${scene.percent}%` }" /></div><strong>{{ scene.count }} 家</strong>
            </div>
          </div>
          <div v-else class="resource-error"><h2>暂无场景数据</h2><p>当前可见产品、服务和需求尚未形成可统计的场景标签。</p></div>
          <div class="insight-callout"><span>口径</span><p><b>真实业务数据</b>场景数量来自当前账号可见的产品、服务和合作需求，不使用演示数据补足空结果。</p></div>
        </article>

        <article class="panel">
          <div class="panel-header"><div><h2>最新动态</h2><p>政策、会员和协作变化</p></div><button class="text-button" type="button" :disabled="loading" @click="load">刷新</button></div>
          <div v-if="data.activities.length" class="activity-list">
            <div v-for="activity in data.activities" :key="activity.id" class="activity-item">
              <span class="activity-icon" :class="activity.type"><component :is="activityIcons[activity.type]" aria-hidden="true" /></span>
              <div><strong>{{ activity.title }}</strong><p>{{ displayActivityDetail(activity.detail) }}</p><small>{{ displayActivityTime(activity.time) }}</small></div>
            </div>
          </div>
          <div v-else class="resource-error"><h2>暂无最新动态</h2><p>当前数据范围内尚无政策、匹配或协作变化。</p></div>
        </article>
      </section>

      <section class="panel task-panel">
        <div class="panel-header"><div><h2>待推进协作</h2><p>需要协会协调或跟进的重点事项</p></div><RouterLink class="text-button" to="/collaborations">全部事项 →</RouterLink></div>
        <div v-if="data.pendingTasks.length" class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>协作事项</th><th>参与方</th><th>负责人</th><th>阶段</th><th>下一步</th><th>截止日期</th></tr></thead>
            <tbody><tr v-for="task in data.pendingTasks" :key="task.id"><td><strong>{{ task.title }}</strong></td><td>{{ task.participants.join(' × ') }}</td><td>{{ task.owner }}</td><td><StatusBadge :value="task.stage" /></td><td>{{ task.nextAction }}</td><td>{{ task.dueDate }}</td></tr></tbody>
          </table>
        </div>
        <div v-else class="resource-error"><h2>暂无待推进协作</h2><p>当前没有需要协会协调或跟进的事项。</p><RouterLink class="primary-button" to="/collaborations">查看全部协作</RouterLink></div>
      </section>
    </template>
  </div>
</template>
