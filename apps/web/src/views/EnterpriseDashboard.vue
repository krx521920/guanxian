<script setup lang="ts">
import { computed, onMounted } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { useAuth } from '../services/auth'

const auth = useAuth()
const { data, loading, error, load } = useAsyncResource(platformApi.enterpriseDashboard)
const profileTitle = computed(() => {
  const completeness = data.value?.completeness ?? 0
  if (completeness >= 80) return '企业资料已达到较高完整度'
  if (completeness > 0) return '企业资料仍可继续完善'
  return '当前尚无完整的企业资料'
})

function displayPolicyDate(value: string | null | undefined): { month: string; day: string } {
  const matched = value?.match(/^\d{4}-(\d{2})-(\d{2})/)
  return matched ? { month: matched[1], day: matched[2] } : { month: '—', day: '—' }
}
onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="ENTERPRISE WORKSPACE" title="企业工作台" :description="`${auth.user.value?.organization} · 管理能力资产，发现政策与合作机会`">
      <RouterLink class="secondary-button" to="/members">查看企业资料</RouterLink><RouterLink v-if="auth.user.value?.role === 'ENTERPRISE_ADMIN'" class="primary-button" to="/ecosystem?create=demand">+ 发布需求</RouterLink><RouterLink v-else class="primary-button" to="/ecosystem">查看产品与需求</RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="profile-completeness panel">
        <div class="completeness-ring" :style="{ '--progress': `${data.completeness * 3.6}deg` }"><div><strong>{{ data.completeness }}%</strong><span>资料完整度</span></div></div>
        <div><span class="eyebrow">ENTERPRISE PROFILE</span><h2>{{ profileTitle }}</h2><p>完整度由服务端依据当前可见企业字段计算；产品、需求和附件可在对应页面持续维护。</p><RouterLink class="text-button" to="/members">{{ auth.user.value?.role === 'ENTERPRISE_ADMIN' ? '继续完善资料 →' : '查看企业资料 →' }}</RouterLink></div>
        <div class="profile-checks"><RouterLink to="/members">企业基本信息</RouterLink><RouterLink to="/ecosystem">产品与需求</RouterLink><RouterLink to="/attachments">资质与案例</RouterLink><RouterLink to="/matching">生态匹配</RouterLink></div>
      </section>

      <section class="metrics-grid enterprise-metrics"><MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['品', '机', '协', '策'][index]" /></section>

      <section class="content-grid enterprise-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>当前可见匹配</h2><p>来自服务端规则计算和当前身份授权范围</p></div><RouterLink to="/matching" class="text-button">查看更多 →</RouterLink></div>
          <div class="compact-match" v-for="item in data.matches" :key="item.id">
            <div class="score-bubble"><strong>{{ item.score }}</strong><span>匹配度</span></div>
            <div class="compact-main"><div><span class="tag">{{ item.scene }}</span><StatusBadge :value="item.state" /></div><h3>{{ item.demandTitle }}</h3><p>{{ item.demandCompany }} · 推荐方案：{{ item.solution }}</p></div>
          </div>
          <div v-if="!data.matches.length" class="empty-business-state"><b>暂无可见匹配</b><span>请先完善并开放合作需求，或前往匹配工作台查看当前权限范围。</span><RouterLink class="primary-button" to="/matching">进入匹配工作台</RouterLink></div>
        </article>
        <article class="panel">
          <div class="panel-header"><div><h2>政策影响提醒</h2><p>与企业业务相关的最新政策</p></div><RouterLink to="/policies" class="text-button">政策中心 →</RouterLink></div>
          <div class="policy-compact" v-for="policy in data.recommendedPolicies" :key="policy.id"><span class="date-block"><strong>{{ displayPolicyDate(policy.publishDate).month }}</strong><small>{{ displayPolicyDate(policy.publishDate).day }}</small></span><div><StatusBadge :value="policy.status" /><h3>{{ policy.title }}</h3><p>{{ policy.authority }}</p></div></div>
          <div v-if="!data.recommendedPolicies.length" class="empty-business-state"><b>暂无政策提醒</b><span>当前账号的数据范围内没有可展示的政策记录。</span><RouterLink class="primary-button" to="/policies">查看政策中心</RouterLink></div>
        </article>
      </section>
    </template>
  </div>
</template>
