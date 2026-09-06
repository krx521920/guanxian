<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import BusinessResultDialog from './BusinessResultDialog.vue'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { businessResultText, followupSelection, memberStatus, type BusinessResult, type SelectedEnterprise } from '../services/assistant-business-results'
import { checkMemberFit, fitFields, fitStatuses, normalizeFitCriteria, type FitCriterion } from '../services/assistant-fit-check'

const props = defineProps<{ enterprises: SelectedEnterprise[]; initialCriteria: FitCriterion[]; returnFocus?: HTMLElement | null }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuth()
const selected = ref(followupSelection(props.enterprises))
const criteria = ref<FitCriterion[]>(props.initialCriteria.length ? props.initialCriteria.map(c => ({ ...c })) : [{ field: 'capabilities', value: '' }])
const result = ref<BusinessResult | null>(null)
const resultPanel = ref<HTMLElement | null>(null)
const busy = ref(false), error = ref(''), copied = ref(false)
let revision = 0, controller: AbortController | undefined
function invalidate() { revision++; controller?.abort(); controller = undefined; busy.value = false; result.value = null; copied.value = false; error.value = '' }
watch([selected, criteria], invalidate, { deep: true, flush: 'sync' })
onBeforeUnmount(invalidate)
const valid = computed(() => { try { normalizeFitCriteria(criteria.value); return selected.value.length > 0 } catch { return false } })
const validation = computed(() => { try { normalizeFitCriteria(criteria.value); return '' } catch (e) { return (e as Error).message } })
const stateLabel = (state: string) => ({ MATCHED: '登记字段支持', UNMET: '登记值不同', INSUFFICIENT: '资料不足' }[state] || state)
const failureLabel = (status: string) => ({ FORBIDDEN: '资料权限不足；未返回任何企业的部分结果。', UNAVAILABLE: '企业已不可用，请关闭并重新选择。', FAILED: '查询失败，不能据此认定没有符合条件的企业。', INVALID: '条件无效，请修改后重新核对。' }[status] || '核对未成功。')
function changeField(index: number) { criteria.value[index].value = criteria.value[index].field === 'status' ? 'ACTIVE' : '' }
async function submit() {
  if (!valid.value || busy.value) return
  invalidate()
  const current = revision
  controller = new AbortController(); busy.value = true
  try {
    const response = await checkMemberFit(selected.value.map(i => i.id), criteria.value, auth.user.value?.associationId || undefined, controller.signal)
    if (current === revision) {
      result.value = response.result
      await nextTick()
      if (current === revision) { resultPanel.value?.focus({ preventScroll: true }); resultPanel.value?.scrollIntoView({ block: 'start' }) }
    }
  } catch (reason) {
    if (current === revision) error.value = reason instanceof ApiRequestError
      ? reason.status === 403 ? '当前身份或组织无权执行此核对，未使用旧资料。' : '核对服务不可用或请求失败，未使用旧资料。'
      : '核对结果未通过一致性检查，未展示结果。'
  } finally { if (current === revision) busy.value = false }
}
async function copy() {
  if (!result.value) return
  const current = revision; copied.value = false; error.value = ''
  try { await navigator.clipboard.writeText(businessResultText(result.value, false)); if (current === revision) copied.value = true }
  catch { if (current === revision) error.value = '尚未复制到剪贴板，请手动选择核对结果。' }
}
</script>
<template>
  <BusinessResultDialog title="编辑条件并重新核对" :return-focus="returnFocus" @close="emit('close')">
    <p class="fit-notice">只核对所选企业的当前登记字段，不调用模型、不修改资料、不生成正式匹配。支持不代表已验证履约能力。</p>
    <section aria-label="条件核对企业范围" class="fit-selection">
      <strong>所选 {{ selected.length }} 家企业 · 名称为选择时快照</strong>
      <div class="fit-chips"><span v-for="item in selected" :key="item.id">{{ item.name }}<button type="button" :aria-label="`移除核对企业 ${item.name}`" :disabled="busy || selected.length === 1" @click="selected = selected.filter(i => i.id !== item.id)">×</button></span></div>
    </section>
    <form class="fit-form" @submit.prevent="submit">
      <fieldset :disabled="busy"><legend>逐项条件（最多 8 项，每项 80 字）</legend>
        <div v-for="(criterion, index) in criteria" :key="index" class="fit-row">
          <label>条件 {{ index + 1 }} 字段<select v-model="criterion.field" :aria-label="`条件 ${index + 1} 字段`" @change="changeField(index)"><option v-for="(label, field) in fitFields" :key="field" :value="field">{{ label }}</option></select></label>
          <label>条件 {{ index + 1 }} 内容<select v-if="criterion.field === 'status'" v-model="criterion.value" :aria-label="`条件 ${index + 1} 内容`"><option v-for="(label, value) in fitStatuses" :key="value" :value="value">{{ label }}</option></select><input v-else v-model="criterion.value" :aria-label="`条件 ${index + 1} 内容`" maxlength="80" placeholder="填写明确的登记值或关键词" /></label>
          <button type="button" :disabled="criteria.length === 1" :aria-label="`删除条件 ${index + 1}`" @click="criteria.splice(index, 1)">删除</button>
          <small>{{ ['category', 'status'].includes(criterion.field) ? '按登记值精确核对' : '按登记文字包含关键词核对；未提及或否定表达记为资料不足' }}</small>
        </div>
        <button type="button" :disabled="criteria.length === 8" @click="criteria.push({ field: 'capabilities', value: '' })">添加条件</button>
      </fieldset>
      <p v-if="validation" class="fit-validation">{{ validation }}</p>
      <div class="fit-actions"><button type="submit" :disabled="!valid || busy">{{ busy ? '正在重新核对…' : '按这些条件重新核对' }}</button><button v-if="busy" type="button" @click="invalidate">取消核对</button></div>
      <p class="fit-notice">修改条件会清除旧核对结果；提交时重新检查权限和资料。不是全库筛选，不自动排序或打分。</p>
    </form>
    <p v-if="error" role="alert" class="fit-error">{{ error }}</p>
    <section v-if="result" ref="resultPanel" tabindex="-1" aria-label="手动条件核对结果" class="fit-result">
      <h3>{{ result.label }}</h3><p>{{ result.scope }}</p><p>查询时间：{{ new Date(result.queriedAt).toLocaleString('zh-CN', { hour12: false }) }} · 共核对 {{ result.total }} 家</p>
      <p v-if="result.status !== 'OK'" role="alert" class="fit-error">{{ failureLabel(result.status) }}</p>
      <template v-else>
        <p>条件由您手动确认。以下逐项结论仅适用于本次查询，不代表推荐排序。</p>
        <div class="fit-table-scroll" tabindex="0" aria-label="条件证据表，可横向滚动"><table><thead><tr><th scope="col">核对条件</th><th v-for="item in result.items" :key="item.id" scope="col">{{ item.name }}</th></tr></thead><tbody>
          <tr v-for="(criterion, index) in criteria" :key="index"><th scope="row">{{ fitFields[criterion.field] }}：{{ criterion.field === 'status' ? fitStatuses[criterion.value as keyof typeof fitStatuses] : criterion.value }}</th>
            <td v-for="item in result.items" :key="item.id" :class="`fit-${item.evidence[index].state.toLowerCase()}`"><strong>{{ stateLabel(item.evidence[index].state) }}</strong><p>登记值：{{ criterion.field === 'status' ? memberStatus(item.evidence[index].observed) : item.evidence[index].observed || '未提供' }}</p><small>{{ item.evidence[index].explanation }}</small></td>
          </tr></tbody></table></div>
      </template>
      <p class="fit-query-id">查询编号：{{ result.id }}</p><button type="button" @click="copy">{{ copied ? '已复制手动核对快照' : '复制核对结果与依据' }}</button>
    </section>
  </BusinessResultDialog>
</template>
<style scoped>
.fit-result{scroll-margin-top:90px;outline:none}.fit-result:focus-visible{outline:2px solid #528bc7;outline-offset:4px}
.fit-notice,.fit-validation{font-size:13px;color:#5d7086}.fit-selection{padding:12px;border-radius:10px;background:#edf3fa}.fit-chips,.fit-actions{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.fit-chips span{padding:4px 8px;background:#fff;border:1px solid #d8e2ee;border-radius:7px;overflow-wrap:anywhere}.fit-chips button{border:0;padding:1px 6px;margin-left:6px}.fit-form{margin:20px 0}fieldset{border:0;padding:0;min-width:0}legend{font-weight:650;margin-bottom:12px}.fit-row{display:grid;grid-template-columns:minmax(140px,1fr) minmax(200px,2fr) auto;gap:10px;margin-bottom:16px;align-items:end}.fit-row label{display:grid;gap:4px;font-size:13px}.fit-row small{grid-column:1/-1;font-size:12px;color:#63768b}input,select,button{font:inherit;border:1px solid #bdcfe1;border-radius:8px;padding:8px 10px;background:#fff;color:#245077;min-width:0}input,select{width:100%;box-sizing:border-box}button{cursor:pointer}button:disabled{opacity:.5;cursor:default}button[type=submit]{background:#1d5389;color:white}.fit-error{color:#a52f35}.fit-result{border-top:1px solid #dce5ef;padding-top:12px}.fit-table-scroll{overflow-x:auto}.fit-table-scroll table{width:100%;border-collapse:collapse;font-size:14px}.fit-table-scroll th,.fit-table-scroll td{border:1px solid #dce5ef;padding:12px;vertical-align:top;text-align:left;min-width:185px;max-width:280px;overflow-wrap:anywhere}.fit-table-scroll th:first-child{min-width:145px}.fit-table-scroll thead{background:#edf3fa}.fit-table-scroll p{margin:6px 0}.fit-matched strong{color:#246c58}.fit-unmet strong{color:#a24d48}.fit-insufficient strong{color:#7a651e}.fit-query-id{font-size:11px;overflow-wrap:anywhere;color:#62738a}@media(max-width:600px){.fit-row{grid-template-columns:minmax(0,1fr) auto}.fit-row label{grid-column:1/-1}.fit-row>button{grid-column:2}.fit-row small{grid-row:auto}}
</style>
