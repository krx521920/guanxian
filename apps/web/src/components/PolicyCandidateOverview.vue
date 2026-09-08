<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../services/auth'
import { policyCandidateOverview, assessmentKind, candidateTimeWarning, type PolicyCandidateOverview } from '../services/policy-candidates'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import AsyncResourceState from './AsyncResourceState.vue'
const emit = defineEmits<{ select: [id: string] }>()
const auth = useAuth()
const result = ref<PolicyCandidateOverview | null>(null)
const error = ref<PageResourceError | null>(null)
const loading = ref(false); const query = ref(''); const applied = ref(''); const page = ref(0)
const hasNext = computed(() => result.value !== null && (page.value + 1) * result.value.size < result.value.visiblePolicyCount)
let sequence = 0
async function load() {
  const epoch = ++sequence
  result.value = null; error.value = null; loading.value = true
  try {
    let data = await policyCandidateOverview(applied.value, page.value)
    if (epoch !== sequence) return
    if (page.value > 0 && data.visiblePolicyCount <= page.value * data.size) {
      page.value = Math.max(0, Math.ceil(data.visiblePolicyCount / data.size) - 1)
      data = await policyCandidateOverview(applied.value, page.value)
    }
    if (epoch === sequence) result.value = data
  } catch (reason) { if (epoch === sequence) error.value = safePageResourceError(reason) }
  finally { if (epoch === sequence) loading.value = false }
}
function search() { applied.value = query.value.trim(); page.value = 0; void load() }
function next(delta: number) { page.value += delta; void load() }
watch(() => JSON.stringify([auth.user.value?.id, auth.user.value?.role, auth.user.value?.associationId, auth.user.value?.enterpriseId]), () => {
  page.value = 0; query.value = ''; applied.value = ''; void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <section class="panel candidate-overview" aria-label="自动企业关联总览">
    <h2>政策与企业 · 自动候选关联</h2>
    <p>按当前可见范围逐页对比已发布政策与企业业务资料，无需逐对选择。以下是待核实线索，不是正式影响分析或适用结论；不会自动审核或发送通知。</p>
    <form @submit.prevent="search"><input v-model="query" aria-label="搜索自动关联政策" placeholder="按政策关键词查询" maxlength="100" /><button class="secondary-button" :disabled="loading">查询</button></form>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="result">
      <p role="status">可见政策 {{ result.visiblePolicyCount }} 条，本页对比 {{ result.items.length }} 条已发布政策、{{ result.examinedEnterpriseCount }} 家正常企业；每条先展示最多 3 家候选。</p>
      <p v-if="result.truncated" class="warning">当前最多扫描 2000 家，候选数只代表已扫描范围。请进入政策详情按企业名称缩小范围。</p>
      <article v-for="item in result.items" :key="item.policyId">
        <div><h3>{{ item.policyTitle }}</h3><span>{{ item.candidateCount }} 家候选</span></div>
        <p v-if="!item.candidateCount">暂无足够业务交集，不代表政策不适用。</p>
        <ul v-else><li v-for="candidate in item.examples" :key="candidate.enterpriseId"><RouterLink :to="{ path: '/members', query: { memberId: candidate.enterpriseId } }">{{ candidate.enterpriseName }}</RouterLink><span> · {{ candidate.evidence.map(e => e.topic).join('、') }}<template v-if="candidate.assessment"> · {{ assessmentKind(candidate.assessment.kind) }}</template></span><p v-if="candidateTimeWarning(candidate)" class="warning">{{ candidateTimeWarning(candidate) }}</p></li></ul>
        <button class="text-button" type="button" @click="emit('select', item.policyId)">核对依据与全部候选 →</button>
      </article>
      <p v-if="!result.items.length">本页暂无可分析的已发布政策，可切换下一页或调整查询。</p>
      <nav aria-label="自动关联分页"><button class="secondary-button" type="button" :disabled="page === 0" @click="next(-1)">上一页</button><span>第 {{ page + 1 }} 页</span><button class="secondary-button" type="button" :disabled="!hasNext" @click="next(1)">下一页</button><button class="text-button" type="button" @click="load">刷新关联</button></nav>
    </template>
  </section>
</template>
<style scoped>
.candidate-overview { padding: 24px; margin: 24px 0; text-align: left; min-width: 0; }
h2 { font-size: 22px; } h3 { font-size: 16px; margin: 0; }
p, li { line-height: 1.7; overflow-wrap: anywhere; } p, li span { color: var(--text-secondary, #526174); font-size: 13px; }
form, nav, article > div { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
input { min-width: 0; max-width: 100%; flex: 1; padding: 10px; border: 1px solid var(--line); border-radius: 8px; }
article { padding: 16px 0; border-top: 1px solid var(--line); } article > div { justify-content: space-between; }
article ul { padding-left: 20px; } .warning { color: #895000; }
@media (max-width: 520px) { .candidate-overview { padding: 14px; } }
</style>
