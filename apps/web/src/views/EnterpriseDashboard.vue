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
    <PageHeader eyebrow="ENTERPRISE WORKSPACE" title="企业工作台" :description="`${auth.user.value?.organization} · 管理能力资产，发现政策与合作机会`">
      <RouterLink class="secondary-button" to="/members">查看企业资料</RouterLink><RouterLink v-if="auth.user.value?.role === 'ENTERPRISE_ADMIN'" class="primary-button" to="/ecosystem?create=demand">+ 发布需求</RouterLink><RouterLink v-else class="primary-button" to="/ecosystem">查看产品与需求</RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="data">
      <section class="profile-completeness panel">
        <div class="completeness-ring" :style="{ '--progress': `${data.completeness * 3.6}deg` }"><div><strong>{{ data.completeness }}%</strong><span>资料完整度</span></div></div>
        <div><span class="eyebrow">ENTERPRISE PROFILE</span><h2>企业资料完整度 {{ data.completeness }}%</h2><p>完整度来自当前企业档案的实时字段统计；产品、需求和附件可在对应页面持续维护。</p><RouterLink class="text-button" to="/members">{{ auth.user.value?.role === 'ENTERPRISE_ADMIN' ? '继续完善资料 →' : '查看企业资料 →' }}</RouterLink></div>
        <div class="profile-checks"><RouterLink to="/members">企业基本信息</RouterLink><RouterLink to="/ecosystem">产品与需求</RouterLink><RouterLink to="/attachments">资质与案例</RouterLink><RouterLink to="/matching">生态匹配</RouterLink></div>
      </section>

      <section class="metrics-grid enterprise-metrics"><MetricCard v-for="(metric, index) in data.metrics" :key="metric.label" :metric="metric" :icon="['品', '机', '协', '策'][index]" /></section>

      <section class="content-grid enterprise-grid">
        <article class="panel">
          <div class="panel-header"><div><h2>为您推荐的商机</h2><p>根据企业能力与场景偏好筛选，仅供辅助参考，是否对接由企业自行决定</p></div><RouterLink to="/matching" class="text-button">查看更多 →</RouterLink></div>
          <div class="compact-match" v-for="item in data.matches" :key="item.id">
            <div class="score-bubble"><strong>{{ item.score }}</strong><span>参考评分</span></div>
            <div class="compact-main"><div><span class="tag">{{ item.scene }}</span><StatusBadge :value="item.state" /></div><h3>{{ item.demandTitle }}</h3><p>{{ item.demandCompany }} · 推荐方案：{{ item.solution }}</p></div>
          </div>
          <div class="data-source panel-source"><span>数据来源：<b>已审核需求库 + 在架能力库</b></span><span>定位：<b>辅助决策，不替代企业决定</b></span></div>
        </article>
        <article class="panel">
          <div class="panel-header"><div><h2>政策影响提醒</h2><p>与企业业务相关的最新政策</p></div><RouterLink to="/policies" class="text-button">政策中心 →</RouterLink></div>
          <div class="policy-compact" v-for="policy in data.recommendedPolicies" :key="policy.id"><span class="date-block"><strong>{{ policy.publishDate.slice(5, 7) }}</strong><small>{{ policy.publishDate.slice(8) }}</small></span><div><StatusBadge :value="policy.status" /><h3>{{ policy.title }}</h3><p>{{ policy.authority }}</p></div></div>
          <div class="data-source panel-source"><span>数据来源：<b>政策标准中心</b></span><span>原文以发布单位官方渠道为准</span></div>
        </article>
      </section>
    </template>
  </div>
</template>
