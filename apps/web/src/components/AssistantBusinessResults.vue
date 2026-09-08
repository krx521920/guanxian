<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { platformApi } from '../services/platform-api'
import { ApiRequestError } from '../services/http'
import { businessResultText, followupSelection, fieldValue, memberFields, memberItem, type BusinessItem, type BusinessResult, type SelectedEnterprise } from '../services/assistant-business-results'
import type { MemberProfile } from '../types/domain'
import BusinessResultDialog from './BusinessResultDialog.vue'
import MemberProfileDialog from './MemberProfileDialog.vue'
import MemberFitCheckDialog from './MemberFitCheckDialog.vue'
import { criteriaFromResult, type FitCriterion } from '../services/assistant-fit-check'
import SourceDirectory from './SourceDirectory.vue'
const props = defineProps<{ results: BusinessResult[]; incomplete: boolean; followupDisabled?: boolean }>()
const emit = defineEmits<{ followup: [items: SelectedEnterprise[]] }>()
const copied = ref<string | null>(null)
const expandedSource = ref<string | null>(null)
const selected = ref<string[]>([])
const comparison = ref<BusinessItem[]>([])
const comparedAt = ref('')
const detail = ref<MemberProfile | null>(null)
const fitCheck = ref<{ enterprises: SelectedEnterprise[]; criteria: FitCriterion[] } | null>(null)
const busy = ref(false)
const error = ref('')
const returnFocus = ref<HTMLElement | null>(null)
let revision = 0
onBeforeUnmount(() => { revision++ })
const selectable = computed(() => [...new Map(props.results.flatMap(r => r.status === 'OK' ? r.items : []).filter(i => i.target === 'MEMBER').map(i => [i.id, i])).values()])
const failure = (reason: unknown) => reason instanceof ApiRequestError && (reason.status === 403 || reason.status === 404)
  ? '资料已不可用或权限发生变化，请重新查询。' : '最新资料加载失败，未展示旧数据；请稍后重试。'
async function openDetail(id: string) {
  if (busy.value) return
  returnFocus.value = document.activeElement as HTMLElement | null
  const current = ++revision
  detail.value = null; error.value = ''; busy.value = true
  try { const value = await platformApi.member(id); if (revision === current) detail.value = value.member }
  catch (reason) { if (revision === current) error.value = failure(reason) }
  finally { if (revision === current) busy.value = false }
}
async function compare(ids = selected.value) {
  if (busy.value || ids.length < 2 || ids.length > 4) return
  returnFocus.value = document.activeElement as HTMLElement | null
  const current = ++revision
  comparison.value = []; error.value = ''; busy.value = true
  try {
    const values = await Promise.all(ids.map(id => platformApi.member(id)))
    if (revision === current) { comparison.value = values.map(v => memberItem(v.member)); comparedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }) }
  } catch (reason) { if (revision === current) error.value = failure(reason) }
  finally { if (revision === current) busy.value = false }
}
function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter(x => x !== id) : selected.value.length < 4 ? [...selected.value, id] : selected.value
}
function followup(items: SelectedEnterprise[]) {
  if (!props.followupDisabled && items.length) emit('followup', followupSelection(items))
}
function openFitCheck(items: SelectedEnterprise[], source?: BusinessResult) {
  if (busy.value || props.followupDisabled || !items.length || items.length > 4) return
  returnFocus.value = document.activeElement as HTMLElement | null
  fitCheck.value = { enterprises: followupSelection(items), criteria: source ? criteriaFromResult(source) : [] }
}
const editableFit = (result: BusinessResult) => ['RECOMMENDATIONS', 'MEMBER_FIT_CHECK'].includes(result.kind) && result.items.length > 0
async function copyResult(result: BusinessResult) {
  copied.value = null; error.value = ''
  const current = revision
  try {
    await navigator.clipboard.writeText(businessResultText(result, props.incomplete))
    if (revision === current) copied.value = result.id
  } catch { if (revision === current) error.value = '复制失败，请手动选择资料文字；尚未复制到剪贴板。' }
}
const statusText = (status: string) => ({ FORBIDDEN: '没有查询权限', FAILED: '查询失败，不代表没有数据', INVALID: '查询条件无效', UNAVAILABLE: '资料不存在或已不可用' }[status] || status)
const evidenceText = (state: string) => ({ MATCHED: '符合登记条件', UNMET: '登记值不符合', INSUFFICIENT: '资料不足' }[state] || state)
const canCompare = (result: BusinessResult) => result.kind === 'COMPARISON' || (result.kind === 'SELECTED_MEMBERS' && result.items.length > 1)
</script>
<template>
  <section class="business-results" aria-label="可核对的业务结果">
    <div class="business-results-heading"><strong>查询凭据与业务结果</strong><small>来自实际查询，非模型生成卡片</small></div>
    <p v-if="incomplete" class="receipt-note">以下查询已有返回；文字解释尚未完成，不代表整项任务已完成。</p>
    <p v-if="error" role="alert" class="receipt-error">{{ error }}</p>
    <details v-for="result in results" :key="result.id" class="query-receipt" open>
      <summary>{{ result.label }}<span v-if="result.status === 'OK'"> · 共 {{ result.total }} 条 · 展示 {{ result.items.length }} 条</span></summary>
      <p class="receipt-scope">{{ result.scope }}</p>
      <button type="button" @click="copyResult(result)">{{ copied === result.id ? '已复制查询快照' : '复制结果与依据' }}</button>
      <dl class="receipt-meta"><div v-for="(value, key) in result.filters" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></div><div><dt>查询时间</dt><dd>{{ new Date(result.queriedAt).toLocaleString('zh-CN', { hour12: false }) }}</dd></div></dl>
      <p v-if="result.status !== 'OK'" class="receipt-error">{{ statusText(result.status) }}</p>
      <p v-else-if="!result.items.length">当前查询范围未找到记录。</p>
      <template v-else>
        <button v-if="editableFit(result)" type="button" :disabled="busy || followupDisabled" @click="openFitCheck(result.items, result)">编辑这些条件并重新核对</button>
        <button v-if="canCompare(result)" type="button" :disabled="busy" @click="compare(result.items.map(i => i.id))">打开对比表（重新查询）</button>
        <article v-for="item in result.items" :key="item.id" class="business-card">
          <strong class="business-name">{{ item.name }}</strong>
          <section v-if="item.target === 'SOURCE' && item.source" class="source-receipt" aria-label="公开资料来源">
            <p>{{ item.source.kind === 'TENDER' ? '外部招采或历史结果' : '企业活动与公开动态' }} · 来源 {{ item.source.sourceId }}<template v-if="item.source.evidenceRecordId"> · 证据 {{ item.source.evidenceRecordId }}</template></p>
            <p>来源核验截至 {{ item.source.checkedOn || '未登记' }}，不是今日实时复核。角色、金额口径和状态需结合原文核对。</p>
            <div class="source-receipt-actions">
              <a v-if="item.source.sourceUrl" :href="item.source.sourceUrl" target="_blank" rel="noopener noreferrer">来源原文 ↗</a>
              <span v-else>暂无安全可用的原文链接</span>
              <a v-for="(url, index) in item.source.supportingUrls" :key="url" :href="url" target="_blank" rel="noopener noreferrer">补充依据 {{ index + 1 }} ↗</a>
              <button type="button" :aria-expanded="expandedSource === item.id" @click="expandedSource = expandedSource === item.id ? null : item.id">{{ expandedSource === item.id ? '收起资料' : '重新核对资料' }}</button>
            </div>
            <SourceDirectory v-if="expandedSource === item.id" :kind="item.source.kind" :record-id="item.id" />
          </section>
          <dl v-if="item.target === 'MEMBER'" class="card-fields"><div v-for="(label, key) in memberFields" :key="key"><dt>{{ label }}</dt><dd>{{ fieldValue(item, String(key)) }}</dd></div></dl>
          <dl v-else class="card-fields"><div v-for="(value, key) in item.fields" :key="key"><dt>{{ key }}</dt><dd>{{ value || '未提供' }}</dd></div></dl>
          <section v-if="item.evidence.length" class="fit-evidence" aria-label="推荐条件核对"><p>条件来自本轮工具参数，请核对是否与您的要求一致。这里只核对已登记资料。</p><div v-for="(evidence, index) in item.evidence" :key="index" :class="`evidence-${evidence.state.toLowerCase()}`"><strong>{{ evidenceText(evidence.state) }} · {{ evidence.criterion }}</strong><p>依据字段：{{ memberFields[evidence.field] }}；登记值：{{ evidence.observed || '未提供' }}</p><p>{{ evidence.explanation }}</p></div></section>
          <div v-if="item.target === 'MEMBER'" class="card-actions"><button type="button" :disabled="busy" @click="openDetail(item.id)">查看详情</button><button type="button" :aria-pressed="selected.includes(item.id)" :disabled="busy || (!selected.includes(item.id) && selected.length === 4)" @click="toggle(item.id)">{{ selected.includes(item.id) ? '取消对比' : '加入对比' }}</button></div>
          <button v-if="item.target === 'MEMBER'" class="followup-button" type="button" :disabled="followupDisabled" @click="followup([item])">追问这家</button>
          <button v-if="item.target === 'MEMBER'" class="followup-button" type="button" :disabled="busy || followupDisabled" @click="openFitCheck([item])">按条件核对这家</button>
        </article>
      </template>
      <small class="receipt-id">查询编号 {{ result.id }}</small>
    </details>
    <div v-if="selectable.length >= 2" class="comparison-action"><span>已选 {{ selected.length }}/4 家</span><button type="button" :disabled="busy || selected.length < 2" @click="compare()">{{ busy ? '正在核验…' : '对比所选企业' }}</button></div>
    <button v-if="selectable.length" type="button" class="followup-button" :disabled="followupDisabled || !selected.length" @click="followup(selected.map(id => selectable.find(item => item.id === id)!).filter(Boolean))">追问所选企业</button>
    <button v-if="selectable.length" type="button" class="followup-button" :disabled="busy || followupDisabled || !selected.length" @click="openFitCheck(selected.map(id => selectable.find(item => item.id === id)!).filter(Boolean))">按条件核对所选企业</button>
    <MemberFitCheckDialog v-if="fitCheck" :enterprises="fitCheck.enterprises" :initial-criteria="fitCheck.criteria" :return-focus="returnFocus" @close="fitCheck = null" />
    <MemberProfileDialog v-if="detail" :member="detail" :return-focus="returnFocus" @close="detail = null" />
    <BusinessResultDialog v-if="comparison.length" title="会员企业对比" :return-focus="returnFocus" @close="comparison = []">
      <p>已重新读取当前授权档案 · {{ comparedAt }}。未提供的资料不等于没有能力。</p>
      <p class="receipt-scope">窄屏可左右滑动表格，查看完整企业资料。</p>
      <div class="comparison-scroll" tabindex="0" aria-label="企业对比表，可横向滚动"><table><thead><tr><th scope="col">对比字段</th><th v-for="item in comparison" :key="item.id" scope="col">{{ item.name }}</th></tr></thead><tbody><tr v-for="(label, key) in memberFields" :key="key"><th scope="row">{{ label }}</th><td v-for="item in comparison" :key="item.id">{{ fieldValue(item, String(key)) }}</td></tr></tbody></table></div>
    </BusinessResultDialog>
  </section>
</template>
<style scoped>
.source-receipt { margin: 12px 0; padding: 12px; border: 1px solid #dce5ef; border-radius: 8px; font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.source-receipt-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.source-receipt a { color: #24558c; }
.followup-button{margin-top:8px}
.business-results{border-top:1px solid #d8e2ed;margin-top:18px;padding-top:14px;font-size:13px;line-height:1.6}.business-results-heading{display:grid;gap:3px}.business-results-heading small,.receipt-scope,.receipt-id{color:#65768a}.query-receipt{margin-top:14px}.query-receipt summary{cursor:pointer;font-weight:650;color:#254d75}.query-receipt summary span{font-size:12px;font-weight:400}.receipt-scope{font-size:12px;margin:8px 0}.receipt-meta{margin:8px 0;font-size:12px}.receipt-meta div{display:flex;gap:10px}.receipt-meta dt{color:#65768a;min-width:56px}dd{margin:0;overflow-wrap:anywhere}.business-card{border:1px solid #d9e3ed;background:#fff;border-radius:12px;padding:13px;margin:10px 0}.business-name{font-size:15px;color:#163c62;overflow-wrap:anywhere}.card-fields{margin:10px 0;display:grid;gap:6px}.card-fields div{display:grid;grid-template-columns:86px minmax(0,1fr);gap:8px}.card-fields dt{color:#61758c}.card-actions,.comparison-action{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.comparison-action{border-top:1px solid #d8e2ed;padding-top:12px;justify-content:space-between}button{font:inherit;border:1px solid #bdcfe1;border-radius:8px;background:white;color:#245a90;padding:6px 10px;cursor:pointer}button[aria-pressed=true]{background:#e8f1fc}button:disabled{opacity:.5;cursor:default}.receipt-note{background:#fff8e6;color:#715515;padding:8px;border-radius:8px}.receipt-error{color:#a52f35}.receipt-id{font-size:10px;overflow-wrap:anywhere}.fit-evidence{margin:12px 0;font-size:12px}.fit-evidence>div{border-left:3px solid #b8c6d5;padding-left:8px;margin:12px 0}.fit-evidence .evidence-matched{border-color:#39877c}.fit-evidence .evidence-unmet{border-color:#b86265}.fit-evidence p{margin:5px 0;overflow-wrap:anywhere}.comparison-scroll{overflow-x:auto}.comparison-scroll table{border-collapse:collapse;width:100%;font-size:14px}.comparison-scroll th,.comparison-scroll td{min-width:180px;max-width:300px;white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid #dce5ef;padding:14px;vertical-align:top;text-align:left}.comparison-scroll th:first-child{min-width:108px}.comparison-scroll thead{background:#edf3fa}
</style>
