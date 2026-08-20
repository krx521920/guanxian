<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'

const { data: items, loading, error, load } = useAsyncResource(platformApi.matches)
const state = ref('全部')
const states = ['全部', '待确认', '已推荐', '沟通中', '已达成']
const filtered = computed(() => (items.value || []).filter((item) => state.value === '全部' || item.state === state.value))
onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="ECOSYSTEM MATCHING" title="生态匹配" description="基于场景、能力、资质与案例，连接真实供需并推动协作">
      <button class="secondary-button">匹配规则</button><button class="primary-button">生成新一轮匹配</button>
    </PageHeader>
    <section class="match-summary">
      <div><span>本月匹配建议</span><strong>38</strong><small>较上月 +12</small></div><div><span>已进入沟通</span><strong>12</strong><small>转化率 31.6%</small></div><div><span>已达成合作</span><strong>7</strong><small>预计金额 860 万</small></div>
      <div class="matching-logic"><span class="ai-chip">AI</span><p><b>匹配依据</b>场景 35% · 能力 25% · 资质 15% · 案例 10% · 交付 10% · 数据质量 5%</p></div>
    </section>
    <div class="segmented match-tabs"><button v-for="itemState in states" :key="itemState" :class="{ active: state === itemState }" @click="state = itemState">{{ itemState }}</button></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="items" class="match-list">
      <article v-for="item in filtered" :key="item.id" class="match-card panel">
        <div class="match-score"><svg viewBox="0 0 44 44"><circle cx="22" cy="22" r="18"/><circle class="score-line" cx="22" cy="22" r="18" :style="{ strokeDashoffset: `${113 - item.score * 1.13}` }"/></svg><div><strong>{{ item.score }}</strong><span>匹配度</span></div></div>
        <div class="match-demand"><span class="eyebrow">需求方 · {{ item.scene }}</span><h2>{{ item.demandTitle }}</h2><p>{{ item.demandCompany }}</p></div>
        <div class="match-arrow"><span>AI 推荐</span>→</div>
        <div class="match-supplier"><span class="eyebrow">能力供给方</span><h2>{{ item.supplierCompany }}</h2><p>{{ item.solution }}</p></div>
        <div class="match-actions"><StatusBadge :value="item.state" /><small>{{ item.updatedAt }}</small><button class="primary-button small">查看匹配详情</button></div>
        <div class="match-reasons"><b>推荐理由</b><span v-for="reason in item.reasons" :key="reason">✓ {{ reason }}</span></div>
      </article>
    </section>
  </div>
</template>
