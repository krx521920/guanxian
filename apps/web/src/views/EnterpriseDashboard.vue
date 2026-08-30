<script setup lang="ts">
import { onMounted } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { useAuth } from '../services/auth'

const auth = useAuth()
const { data, loading, error, load } = useAsyncResource(platformApi.enterpriseDashboard)
onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="企业工作台" :description="`${auth.user.value?.organization} · 管理能力资产，发现政策与合作机会`">
      <button class="secondary-button">预览企业主页</button><button class="primary-button">+ 发布供需</button>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="profile-completeness panel">
        <div class="completeness-ring" :style="{ '--progress': `${data.completeness * 3.6}deg` }"><div><strong>{{ data.completeness }}%</strong><span>资料完整度</span></div></div>
        <div><span class="eyebrow">ENTERPRISE PROFILE</span><h2>企业资料已具备匹配条件</h2><p>补充 2 项产品技术参数和 1 个项目案例，可进一步提升 AI 匹配准确度。</p><button class="text-button">继续完善资料 →</button></div>
        <div class="profile-checks"><span class="done">✓ 基本信息</span><span class="done">✓ 场景能力</span><span class="todo">• 技术参数</span><span class="todo">• 典型案例</span></div>
      </section>

      <section class="metrics-grid enterprise-metrics"><MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['品', '机', '协', '策'][index]" /></section>

      <section class="content-grid enterprise-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>为您推荐的商机</h2><p>根据企业能力与场景偏好智能筛选</p></div><RouterLink to="/matching" class="text-button">查看更多 →</RouterLink></div>
          <div class="compact-match" v-for="item in data.matches" :key="item.id">
            <div class="score-bubble"><strong>{{ item.score }}</strong><span>匹配度</span></div>
            <div class="compact-main"><div><span class="tag">{{ item.scene }}</span><StatusBadge :value="item.state" /></div><h3>{{ item.demandTitle }}</h3><p>{{ item.demandCompany }} · 推荐方案：{{ item.solution }}</p></div>
          </div>
        </article>
        <article class="panel">
          <div class="panel-header"><div><h2>政策影响提醒</h2><p>与企业业务相关的最新政策</p></div><RouterLink to="/policies" class="text-button">政策中心 →</RouterLink></div>
          <div class="policy-compact" v-for="policy in data.recommendedPolicies" :key="policy.id"><span class="date-block"><strong>{{ policy.publishDate.slice(5, 7) }}</strong><small>{{ policy.publishDate.slice(8) }}</small></span><div><StatusBadge :value="policy.status" /><h3>{{ policy.title }}</h3><p>{{ policy.authority }}</p></div></div>
        </article>
      </section>
    </template>
  </div>
</template>
