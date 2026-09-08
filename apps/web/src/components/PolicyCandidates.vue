<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../services/auth'
import { policyCandidates, assessmentKind, candidateTimeWarning, type PolicyCandidate, type PolicyCandidatePage } from '../services/policy-candidates'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import AsyncResourceState from './AsyncResourceState.vue'
import PaginationBar from './PaginationBar.vue'

const props = defineProps<{ policyId: string; canAnalyze?: boolean }>()
const emit = defineEmits<{ analyze: [candidate: PolicyCandidate] }>()
const auth = useAuth()
const result = ref<PolicyCandidatePage | null>(null)
const loading = ref(false)
const error = ref<PageResourceError | null>(null)
const q = ref(''); const appliedQuery = ref(''); const page = ref(0); const size = ref(20)
let sequence = 0
async function load() {
  const current = ++sequence
  result.value = null; error.value = null; loading.value = true
  try {
    let response = await policyCandidates(props.policyId, appliedQuery.value, page.value, size.value)
    if (current !== sequence) return
    if (!response.items.length && response.total > 0 && response.page > 0) {
      page.value = Math.floor((response.total - 1) / response.size)
      response = await policyCandidates(props.policyId, appliedQuery.value, page.value, size.value)
    }
    if (current === sequence) result.value = response
  } catch (reason) { if (current === sequence) error.value = safePageResourceError(reason) }
  finally { if (current === sequence) loading.value = false }
}
function search() { appliedQuery.value = q.value.trim(); page.value = 0; void load() }
function changePage(value: number) { page.value = value; void load() }
function resize(value: number) { size.value = value; page.value = 0; void load() }
watch(() => JSON.stringify([props.policyId, auth.user.value?.id, auth.user.value?.role, auth.user.value?.associationId, auth.user.value?.enterpriseId]), () => {
  q.value = ''; appliedQuery.value = ''; page.value = 0; void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <section class="policy-candidates" aria-label="政策与企业候选关联">
    <header><div><h3>可能相关的企业</h3><p>资料线索匹配 · 不是正式影响分析</p></div><span class="candidate-label">只读候选</span></header>
    <p class="candidate-notice">没有候选不代表政策与企业无关；有候选也不代表政策必然适用。本查询不会自动绑定、审核或发送通知。摘要可生成参考分析；审核发布前仍须归档原文并重新分析。</p>
    <form class="candidate-search" @submit.prevent="search"><input v-model="q" maxlength="100" aria-label="按企业名称筛选候选" placeholder="按企业名称缩小范围" /><button class="secondary-button" type="submit" :disabled="loading">查询候选</button></form>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else-if="result">
      <div class="candidate-scope" role="status">
        <p>查询范围：{{ result.associationName }}{{ result.ownEnterpriseOnly ? ' · 当前企业' : ' · 正常企业' }}<template v-if="result.query"> · 名称包含“{{ result.query }}”</template></p>
        <p>已对比 {{ result.examinedCount }} / {{ result.eligibleEnterpriseCount }} 家，发现 {{ result.total }} 家候选；先区分时间与主体角色，再按命中领域数排序，不是适用概率。</p>
        <p v-if="result.truncated" class="candidate-warning">当前最多对比 2000 家，并非全部企业的完整排名。请按企业名称缩小范围。</p>
        <p>政策版本 {{ result.policyVersion }} · {{ new Date(result.generatedAt).toLocaleString('zh-CN') }} 查询</p>
      </div>
      <dl class="candidate-metadata"><dt>来源适用对象</dt><dd>{{ result.policyMetadata.audience || '待核实' }}</dd><dt>来源适用地区</dt><dd>{{ result.policyMetadata.region || '待核实' }}</dd><dt>来源核验日期</dt><dd>{{ result.policyMetadata.sourceCheckedOn || '未登记' }}（不代表今日已复核）</dd></dl>
      <details class="candidate-limitations"><summary>证据边界与待核实事项</summary><ul><li v-for="item in result.limitations" :key="item">{{ item }}</li></ul></details>
      <p v-if="!result.items.length" class="candidate-empty">当前范围未发现足够的共同业务线索。可补充企业资料或政策摘要后重试，这不是“不适用”结论。</p>
      <article v-for="item in result.items" :key="item.enterpriseId" class="candidate-card">
        <div class="candidate-card-title"><h4>{{ item.enterpriseName }}</h4><span>{{ item.relevance === 'MULTIPLE_CLUES' ? '多项业务线索' : '有限业务线索' }}</span></div>
        <p>{{ item.category || '分类待补充' }} · 企业资料版本 {{ item.enterpriseVersion }}</p>
        <p v-if="candidateTimeWarning(item)" class="candidate-warning">{{ candidateTimeWarning(item) }}</p>
        <h5>为什么可能相关</h5>
        <ul><li v-for="evidence in item.evidence" :key="evidence.topic"><strong>{{ evidence.topic }}</strong>：{{ evidence.policyField }}出现“{{ evidence.policyTerm }}”；{{ evidence.enterpriseField }}出现“{{ evidence.enterpriseTerm }}”。</li></ul>
        <h5>还缺哪些证据</h5><ul class="candidate-gaps"><li v-for="gap in item.missingEvidence" :key="gap">{{ gap }}</li></ul>
        <template v-if="item.assessment"><h5>{{ assessmentKind(item.assessment.kind) }} · 适用性未核实</h5><ul><li v-for="check in item.assessment.checks" :key="check.dimension"><strong>{{ check.dimension }}</strong>：{{ check.explanation }}</li></ul></template>
        <RouterLink class="text-button" :to="{ path: '/members', query: { memberId: item.enterpriseId } }">核对企业资料 →</RouterLink>
        <button v-if="canAnalyze" class="secondary-button" type="button" @click="emit('analyze', item)">以此企业准备分析</button>
      </article>
      <PaginationBar :page="result.page" :size="result.size" :total="result.total" :disabled="loading" @change="changePage" @resize="resize" />
    </template>
  </section>
</template>

<style scoped>
.policy-candidates { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--line); min-width: 0; text-align: left; }
header, .candidate-card-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h3 { margin: 0; font-size: 20px; } h4 { margin: 0; font-size: 16px; } h5 { font-size: 14px; margin: 16px 0 6px; }
p, li, dd { line-height: 1.7; overflow-wrap: anywhere; }
header p, .candidate-card > p, .candidate-scope { color: var(--text-secondary, #526174); font-size: 13px; }
.candidate-label { padding: 5px 10px; border-radius: 6px; background: var(--primary-soft, #eef4fb); color: var(--primary, #204e82); font-size: 12px; }
.candidate-notice, .candidate-empty { background: var(--primary-soft, #eef4fb); padding: 12px; border-radius: 8px; font-size: 13px; }
.candidate-search { display: flex; gap: 8px; margin: 16px 0; }.candidate-search input { flex: 1; min-width: 0; padding: 10px; border: 1px solid var(--line); border-radius: 8px; }
.candidate-scope p { margin: 4px 0; }.candidate-warning { color: #895000; }
.candidate-metadata { display: grid; grid-template-columns: 100px minmax(0, 1fr); gap: 6px 12px; font-size: 13px; } dt { color: var(--text-secondary, #526174); } dd { margin: 0; }
.candidate-limitations { margin: 16px 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; font-size: 13px; } summary { cursor: pointer; }
.candidate-card { padding: 18px; margin-top: 12px; border: 1px solid var(--line); border-radius: 10px; }
.candidate-card-title span { color: var(--primary, #204e82); font-size: 12px; }.candidate-card ul { margin: 6px 0 12px; padding-left: 20px; font-size: 13px; }.candidate-gaps { color: var(--text-secondary, #526174); }
@media (max-width: 520px) { .candidate-search { flex-wrap: wrap; }.candidate-search input { flex-basis: 100%; }.candidate-metadata { grid-template-columns: 1fr; gap: 3px; } dd { margin-bottom: 8px; }.candidate-card { padding: 12px; } }
</style>
