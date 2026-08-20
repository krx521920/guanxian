<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'
import { displayEffectiveDate } from './policy-display'

const { data: items, loading, error, load } = useAsyncResource(platformApi.policies)
const activeLevel = ref('全部')
const keyword = ref('')
const levels = ['全部', '国家', '北京市', '行业协会']
const filtered = computed(() => (items.value || []).filter((item) => {
  const text = `${item.title}${item.authority}${item.summary}${item.tags.join('')}`
  return (activeLevel.value === '全部' || item.level === activeLevel.value) && (!keyword.value || text.includes(keyword.value))
}))
onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="POLICY & STANDARD" title="政策标准" description="向上连接政策与标准，智能识别行业影响和企业机会">
      <button class="secondary-button">政策订阅设置</button><button class="primary-button">+ 收录政策</button>
    </PageHeader>
    <section class="policy-hero panel">
      <div class="ai-orb">AI</div><div><span class="eyebrow">本周政策洞察</span><h2>4 项政策可能影响 38 家会员企业</h2><p>重点涉及信息汇交、燃气安全监测和非开挖修复评价。AI 已按企业场景与产品能力生成影响清单。</p></div><button class="secondary-button">查看影响分析 →</button>
    </section>
    <section class="panel filter-panel policy-filter">
      <div class="segmented"><button v-for="level in levels" :key="level" :class="{ active: activeLevel === level }" @click="activeLevel = level">{{ level }}</button></div>
      <div class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索政策标题、发布单位或关键词" /></div>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="items" class="policy-list">
      <article v-for="policy in filtered" :key="policy.id" class="policy-card panel">
        <div class="policy-level">{{ policy.level }}</div>
        <div class="policy-body"><div class="policy-meta"><StatusBadge :value="policy.status" /><span>{{ policy.category }}</span><span>发布于 {{ policy.publishDate }}</span></div><h2>{{ policy.title }}</h2><p>{{ policy.summary }}</p><div class="tags"><span v-for="tag in policy.tags" :key="tag">{{ tag }}</span></div></div>
        <div class="policy-side"><span>发布单位</span><strong>{{ policy.authority }}</strong><span>施行日期</span><strong>{{ displayEffectiveDate(policy.effectiveDate) }}</strong><button class="text-button">查看详情 →</button></div>
      </article>
    </section>
  </div>
</template>
