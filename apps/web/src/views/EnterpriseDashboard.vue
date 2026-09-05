<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, onMounted } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { useAuth } from '../services/auth'
import { roleLabels } from '../config/roles'

const auth = useAuth()
const { data, loading, error, load } = useAsyncResource(platformApi.enterpriseDashboard)
const profileRoute = '/enterprise/profile'
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
  <div class="enterprise-page">
    <PageHeader title="企业工作台" :description="`${auth.user.value?.organization} · 管理能力资产，发现政策与合作机会`">
      <RouterLink v-if="auth.user.value?.role === 'ENTERPRISE_ADMIN'" class="secondary-button" :to="profileRoute">维护本企业资料</RouterLink><RouterLink v-else class="secondary-button" to="/members">浏览会员目录</RouterLink><RouterLink class="primary-button icon-label-button" to="/matching"><Plus aria-hidden="true" /><span>进入供需匹配</span></RouterLink>
    </PageHeader>
    <p class="enterprise-identity-note" role="note">当前绑定企业：<strong>{{ auth.user.value?.organization }}</strong> · {{ auth.user.value ? roleLabels[auth.user.value.role] : '' }}<span>{{ auth.user.value?.role === 'ENTERPRISE_ADMIN' ? '仅可维护本企业资料，其他企业资料按授权查看。' : '当前为企业只读身份，企业主档由企业管理员维护。' }}</span></p>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="profile-completeness panel">
        <div class="completeness-ring" :style="{ '--progress': `${data.completeness * 3.6}deg` }"><div><strong>{{ data.completeness }}%</strong><span>资料完整度</span></div></div>
        <div><h2>{{ profileTitle }}</h2><p>完整度由服务端依据当前可见企业字段计算；页面不会推断尚缺材料的具体数量。</p><RouterLink class="text-button" :to="profileRoute">{{ auth.user.value?.role === 'ENTERPRISE_ADMIN' ? '维护企业资料' : '查看我的企业' }} →</RouterLink></div>
        <div class="profile-checks"><span :class="data.completeness >= 80 ? 'done' : 'todo'">{{ data.completeness >= 80 ? '✓' : '•' }} 当前完整度 {{ data.completeness }}%</span><span class="todo">• 以最新服务端数据为准</span></div>
      </section>

      <section class="metrics-grid enterprise-metrics"><MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['品', '机', '协', '策'][index]" /></section>

      <section class="content-grid enterprise-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>为您推荐的商机</h2><p>根据企业能力与场景偏好筛选，仅供辅助参考，是否对接由企业自行决定</p></div><RouterLink to="/matching" class="text-button">查看更多 →</RouterLink></div>
          <div class="compact-match" v-for="item in data.matches" :key="item.id">
            <div class="score-bubble"><strong>{{ item.score }}</strong><span>参考评分</span></div>
            <div class="compact-main"><div><span class="tag">{{ item.scene }}</span><StatusBadge :value="item.state" /></div><h3>{{ item.demandTitle }}</h3><p>{{ item.demandCompany }} · 推荐方案：{{ item.solution }}</p></div>
          </div>
          <div v-if="data.matches.length === 0" class="resource-error"><h2>暂无可见匹配</h2><p>请先完善并开放合作需求，或前往匹配工作台查看当前权限范围。</p><RouterLink class="primary-button" to="/matching">进入匹配工作台</RouterLink></div>
          <div class="data-source panel-source"><span>数据来源：<b>已审核需求库 + 在架能力库</b></span><span>定位：<b>辅助决策，不替代企业决定</b></span></div>
        </article>
        <article class="panel">
          <div class="panel-header"><div><h2>政策影响提醒</h2><p>与企业业务相关的最新政策</p></div><RouterLink to="/policies" class="text-button">政策中心 →</RouterLink></div>
          <div class="policy-compact" v-for="policy in data.recommendedPolicies" :key="policy.id"><span class="date-block"><strong>{{ displayPolicyDate(policy.publishDate).month }}</strong><small>{{ displayPolicyDate(policy.publishDate).day }}</small></span><div><StatusBadge :value="policy.status" /><h3>{{ policy.title }}</h3><p>{{ policy.authority }}</p></div></div>
          <div v-if="data.recommendedPolicies.length === 0" class="resource-error"><h2>暂无政策提醒</h2><p>当前账号的数据范围内没有可展示的政策记录。</p><RouterLink class="primary-button" to="/policies">查看政策中心</RouterLink></div>
          <div class="data-source panel-source"><span>数据来源：<b>政策标准中心</b></span><span>原文以发布单位官方渠道为准</span></div>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.enterprise-identity-note { padding: 14px 18px; margin: 0 0 20px; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); color: var(--muted); font-size: 13px; line-height: 1.8; }
.enterprise-identity-note strong { color: var(--ink); }.enterprise-identity-note span { display: block; }
</style>
