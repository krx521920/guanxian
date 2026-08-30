<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { displayStatus } from '../components/status-display'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'

const { data: items, loading, error, load } = useAsyncResource(platformApi.collaborations)
const tab = ref('进行中')
const filtered = computed(() => (items.value || []).filter((item) => {
  const completed = displayStatus(item.stage) === '已完成'
  return tab.value === '全部' || (tab.value === '已完成' ? completed : !completed)
}))

function displayId(value: string): string {
  return value.includes('-') ? value.slice(0, 8).toUpperCase() : value
}
onMounted(load)
</script>

<template>
  <div class="collaborations-page">
    <PageHeader title="协作事项" description="从智能推荐到人工确认、协同推进和结果反馈的完整闭环">
      <button class="primary-button icon-label-button" type="button"><Plus aria-hidden="true" /><span>发起协作</span></button>
    </PageHeader>
    <section class="workflow-strip panel">
      <div><i>1</i><span><b>发现机会</b><small>AI 识别供需</small></span></div><em>→</em><div><i>2</i><span><b>人工确认</b><small>协会审核推荐</small></span></div><em>→</em><div class="active"><i>3</i><span><b>协同推进</b><small>多方沟通办理</small></span></div><em>→</em><div><i>4</i><span><b>结果反馈</b><small>沉淀合作成效</small></span></div>
    </section>
    <div class="list-toolbar"><div class="segmented"><button v-for="value in ['进行中', '已完成', '全部']" :key="value" :class="{ active: tab === value }" @click="tab = value">{{ value }}</button></div><span>{{ filtered.length }} 个事项</span></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="items" class="collaboration-list">
      <article v-for="item in filtered" :key="item.id" class="collaboration-card panel">
        <div class="collab-id" :title="item.id">{{ displayId(item.id) }}</div>
        <div class="collab-main"><div class="collab-title"><StatusBadge :value="item.priority" /><h2>{{ item.title }}</h2></div><p class="participants"><span v-for="participant in item.participants" :key="participant">{{ participant }}</span></p><div class="collab-progress"><div><span>当前进度 · {{ displayStatus(item.stage) }}</span><strong>{{ item.progress }}%</strong></div><div class="progress-track"><i :style="{ width: `${item.progress}%` }" /></div></div></div>
        <div class="collab-next"><span>下一步行动</span><strong>{{ item.nextAction }}</strong><small>负责人：{{ item.owner }} · 截止 {{ item.dueDate }}</small></div>
        <button class="secondary-button small">进入协作</button>
      </article>
    </section>
  </div>
</template>
