<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { Policy, PolicyImpactAnalysis, PolicyQuestionAnswer, PolicyUpsertPayload, Subscription } from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText, splitItems } from './business-form'
import { displayEffectiveDate } from './policy-display'

const auth = useAuth()
const items = ref<Policy[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const impacts = ref<PolicyImpactAnalysis[]>([])
const subscriptions = ref<Subscription[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const activeLevel = ref('全部')
const keyword = ref('')
const policyLevels = ref<string[]>([])
const effectiveFilter = ref('全部')
const audienceFilter = ref('全部')
const sortMode = ref('最新发布')
const effectiveOptions = ['全部', '现行有效', '即将施行', '未定施行日期']
const audienceOptions = ['全部', '全体会员', '本协会', '友好协会', '公开']
const selected = ref<Policy | null>(null)
const createOpen = ref(false)
const subscriptionOpen = ref(false)
const impactOpen = ref(false)
const qaQuestion = ref('')
const qaAnswer = ref<PolicyQuestionAnswer | null>(null)
const qaBusy = ref(false)
const qaError = ref('')
const levels = computed(() => ['全部', ...policyLevels.value])
const canWrite = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const affectedEnterprises = computed(() => new Set(impacts.value.map((item) => item.enterpriseId)).size)

function visibilityLabel(value?: string | null): string {
  return ({ PRIVATE: '仅本单位', ASSOCIATION: '本协会', PARTNERS: '友好协会', MEMBERS: '全体会员', PUBLIC: '公开' } as Record<string, string>)[value || ''] || '会员'
}

function effectiveState(policy: Policy): string {
  if (!policy.effectiveDate) return '未定施行日期'
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return new Date(`${policy.effectiveDate}T00:00:00`).getTime() <= today.getTime() ? '现行有效' : '即将施行'
}

function impactsFor(policy: Policy): PolicyImpactAnalysis[] {
  return impacts.value.filter((item) => item.policyTitle === policy.title)
}

function adviceFor(policy: Policy): string[] {
  if (policy.status === 'DRAFT') return ['补充发布单位、文号与原文链接', '核对摘要与适用对象后提交审核']
  if (policy.status === 'PENDING_REVIEW') return ['核对原文来源与文号', '确认适用对象与可见范围后发布']
  if (policy.status === 'REJECTED') return ['按退回意见修订后重新提交']
  const advice = ['转发相关会员企业并组织学习', '跟踪施行日期，提前提醒受影响企业']
  if (impactsFor(policy).length) advice.push('跟进已归档影响分析中的整改建议')
  return advice
}

const filtered = computed(() => {
  let list = [...items.value]
  if (effectiveFilter.value !== '全部') list = list.filter((policy) => effectiveState(policy) === effectiveFilter.value)
  if (audienceFilter.value !== '全部') list = list.filter((policy) => visibilityLabel(policy.visibility) === audienceFilter.value)
  const factor = sortMode.value === '最新发布' ? -1 : 1
  list.sort((left, right) => left.publishDate.localeCompare(right.publishDate) * factor)
  return list
})
const form = reactive({ title: '', authority: '', documentNumber: '', level: '', category: '', publishDate: '', effectiveDate: '', sourceUrl: '', summary: '', tags: '', visibility: 'MEMBERS' })
let searchTimer: number | null = null

async function load() {
  loading.value = true; error.value = null
  try {
    const [policies, impactPage, visibleLevels] = await Promise.all([
      platformApi.policies(keyword.value.trim(), page.value, size.value, false, activeLevel.value === '全部' ? '' : activeLevel.value),
      platformApi.policyImpacts(),
      platformApi.policyLevels(),
    ])
    items.value = policies.items; total.value = policies.total; page.value = policies.page; size.value = policies.size; impacts.value = impactPage.items; policyLevels.value = visibleLevels
    if (!policies.items.length && policies.total > 0 && page.value > 0) { page.value -= 1; await load() }
  } catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
}

function payload(): PolicyUpsertPayload {
  return { associationId: auth.user.value?.associationId || null, title: form.title.trim(), authority: nullableText(form.authority), documentNumber: nullableText(form.documentNumber), level: nullableText(form.level), category: nullableText(form.category), publishDate: form.publishDate || null, effectiveDate: form.effectiveDate || null, sourceUrl: nullableText(form.sourceUrl), summary: nullableText(form.summary), tags: splitItems(form.tags), visibility: form.visibility }
}

async function createPolicy() {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    await platformApi.createPolicy(payload()); page.value = 0; await load()
    createOpen.value = false; Object.assign(form, { title: '', authority: '', documentNumber: '', level: '', category: '', publishDate: '', effectiveDate: '', sourceUrl: '', summary: '', tags: '', visibility: 'MEMBERS' })
    message.value = '政策已保存为草稿，核对后可提交审核。'
  } catch (reason) { message.value = apiActionMessage(reason, '政策收录失败，请检查必填项。') }
  finally { busy.value = false }
}

async function submit(item: Policy) {
  if (item.version === undefined || busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.submitPolicy(item.id, item.version); replace(saved); selected.value = saved; message.value = '政策已提交审核。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策提交失败。') }
  finally { busy.value = false }
}

async function review(item: Policy, approved: boolean) {
  if (item.version === undefined || busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.reviewPolicy(item.id, item.version, approved, approved ? '' : '请核对来源和摘要后重新提交'); replace(saved); selected.value = saved; message.value = approved ? '政策已审核发布。' : '政策已退回修订。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策审核失败。') }
  finally { busy.value = false }
}

function replace(saved: Policy) { items.value = items.value.map((item) => item.id === saved.id ? saved : item) }

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }

watch([keyword, activeLevel], () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { page.value = 0; void load() }, 300)
})
onBeforeUnmount(() => { if (searchTimer !== null) window.clearTimeout(searchTimer) })

async function openSubscriptions() {
  subscriptionOpen.value = true; busy.value = true; message.value = ''
  try { subscriptions.value = await platformApi.subscriptions() }
  catch (reason) { message.value = apiActionMessage(reason, '订阅设置加载失败。') }
  finally { busy.value = false }
}

async function addSubscription() {
  if (busy.value) return
  busy.value = true
  try { subscriptions.value = [await platformApi.createSubscription({ subscriptionType: 'POLICY', filters: {}, channels: ['IN_APP'] }), ...subscriptions.value]; message.value = '已开启政策站内通知。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策订阅创建失败。') }
  finally { busy.value = false }
}

async function toggleSubscription(item: Subscription) {
  if (busy.value) return
  busy.value = true
  try { const saved = await platformApi.toggleSubscription(item); subscriptions.value = subscriptions.value.map((value) => value.id === saved.id ? saved : value); message.value = '订阅状态已更新。' }
  catch (reason) { message.value = apiActionMessage(reason, '订阅状态更新失败。') }
  finally { busy.value = false }
}

async function askKnowledge() {
  if (!qaQuestion.value.trim() || qaBusy.value) return
  qaBusy.value = true; qaError.value = ''; qaAnswer.value = null
  try {
    qaAnswer.value = await platformApi.askPolicyQuestion(
      qaQuestion.value.trim(),
      5,
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
  } catch (reason) { qaError.value = apiActionMessage(reason, '政策问答失败，请稍后重试。') }
  finally { qaBusy.value = false }
}

async function downloadCitationSource(citation: PolicyQuestionAnswer['citations'][number]) {
  if (!citation.sourceAttachmentId || qaBusy.value) return
  qaBusy.value = true; qaError.value = ''
  try {
    const blob = await platformApi.downloadAttachment(citation.sourceAttachmentId)
    const url = URL.createObjectURL(blob); const anchor = document.createElement('a')
    anchor.href = url; anchor.download = citation.sourceFilename || citation.documentName; anchor.click()
    URL.revokeObjectURL(url)
  } catch (reason) { qaError.value = apiActionMessage(reason, '原始附件下载失败。') }
  finally { qaBusy.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="POLICY & STANDARD" title="政策标准" description="政策从收录、审核、发布到企业影响分析的真实数据闭环">
      <button class="secondary-button" @click="openSubscriptions">政策订阅设置</button><button v-if="canWrite" class="primary-button" @click="createOpen = true">+ 收录政策</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="policy-hero panel">
      <div class="ai-orb">资料</div><div><span class="eyebrow">已归档影响分析</span><h2>{{ impacts.length }} 项分析，覆盖 {{ affectedEnterprises }} 家企业</h2><p v-if="impacts.length">所有数字均来自政策影响分析数据库，点击查看可核对具体政策、企业、结论和分析方法。</p><p v-else>暂无已归档分析，页面不会用模拟数字填充。</p></div><button class="secondary-button" @click="impactOpen = true">查看影响分析 →</button>
    </section>
    <section class="panel policy-qa">
      <div class="panel-header"><div><h2>带出处的政策问答</h2><p>回答仅依据当前身份可见且已入库的资料；每条结论都附带片段出处和追踪编号。</p></div><span class="tag tone-neutral">辅助参考</span></div>
      <form class="policy-qa-form" @submit.prevent="askKnowledge">
        <label class="policy-qa-field"><span>请输入政策、标准或协会资料问题</span><textarea v-model="qaQuestion" rows="3" maxlength="2000" placeholder="例如：资料中对地下管线安全监测提出了哪些要求？" required /></label>
        <div class="policy-qa-actions"><span class="assist-note-inline">问答结果由检索生成，仅供工作参考，正式引用请以政策原文为准。</span><button class="primary-button" :disabled="qaBusy || !qaQuestion.trim()">{{ qaBusy ? '检索生成中…' : '查询资料' }}</button></div>
      </form>
      <div v-if="qaError" class="save-message qa-error" role="alert">{{ qaError }}</div>
      <article v-if="qaAnswer" class="modal-copy policy-qa-answer"><div class="policy-meta"><StatusBadge :value="qaAnswer.retrievalMode === 'HYBRID_VECTOR' ? '混合向量检索' : '关键词检索'" /><span>追踪编号 {{ qaAnswer.traceId }}</span></div><p>{{ qaAnswer.answer }}</p><div v-if="qaAnswer.citations.length" class="impact-list"><article v-for="citation in qaAnswer.citations" :key="citation.chunkId"><div><strong>[{{ citation.order }}] {{ citation.documentName }}</strong><span>版本 {{ citation.version }} · 片段 {{ citation.chunkIndex + 1 }} · 相关度 {{ citation.score.toFixed(3) }}</span></div><p>{{ citation.quote }}</p><a v-if="citation.source" :href="citation.source" target="_blank" rel="noopener noreferrer">查看外部原始来源 ↗</a><button v-else-if="citation.sourceAttachmentId" class="text-button" type="button" :disabled="qaBusy" @click="downloadCitationSource(citation)">下载原始附件 ↓</button><span v-else>该资料未登记外部链接或原始附件。</span></article></div><p v-else>当前可见资料未检索到足够证据，系统没有生成无出处答案。</p></article></section>
    <section class="panel filter-panel policy-filter">
      <div class="segmented" role="tablist" aria-label="按政策层级筛选"><button v-for="level in levels" :key="level" :class="{ active: activeLevel === level }" :aria-selected="activeLevel === level" role="tab" @click="activeLevel = level">{{ level }}</button></div>
      <div class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索政策标题、发布单位或关键词" aria-label="搜索政策" /></div>
    </section>
    <section class="panel filter-panel">
      <select v-model="effectiveFilter" class="filter-select" aria-label="按有效状态筛选"><option v-for="option in effectiveOptions" :key="option" :value="option">{{ option === '全部' ? '有效状态：全部' : `有效状态：${option}` }}</option></select>
      <select v-model="audienceFilter" class="filter-select" aria-label="按适用对象筛选"><option v-for="option in audienceOptions" :key="option" :value="option">{{ option === '全部' ? '适用对象：全部' : `适用对象：${option}` }}</option></select>
      <select v-model="sortMode" class="filter-select" aria-label="排序方式"><option>最新发布</option><option>最早发布</option></select>
      <span class="result-count">本页 {{ filtered.length }} 条，共 {{ total }} 条</span>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="policy-list">
      <article v-for="policy in filtered" :key="policy.id" class="policy-card panel"><div class="policy-level">{{ policy.level || '未分级' }}</div><div class="policy-body"><div class="policy-meta"><StatusBadge :value="displayBusinessStatus(policy.status)" /><StatusBadge :value="effectiveState(policy)" /><span>{{ policy.category || '未分类' }}</span><span>发布于 {{ policy.publishDate || '未公布' }}</span></div><h2>{{ policy.title }}</h2><p>{{ policy.summary || '暂无摘要' }}</p><div class="tags"><span v-for="tag in policy.tags" :key="tag">{{ tag }}</span></div></div><div class="policy-side"><span>发布单位</span><strong>{{ policy.authority || '—' }}</strong><span>施行日期</span><strong>{{ displayEffectiveDate(policy.effectiveDate) }}</strong><span>适用对象</span><strong>{{ visibilityLabel(policy.visibility) }}</strong><span>版本 / 更新</span><strong>v{{ policy.version ?? 1 }} · {{ formatDateTime(policy.updatedAt || policy.publishDate) }}</strong><button class="text-button" :aria-label="`查看政策详情：${policy.title}`" @click="selected = policy">查看详情 →</button></div></article>
      <div v-if="!filtered.length" class="panel empty-business-state"><b>暂无符合条件的政策</b><span>可调整筛选条件，或由协会工作人员收录真实政策。</span><button v-if="canWrite" class="primary-button small" type="button" @click="createOpen = true">+ 收录政策</button></div>
      <div class="data-source"><span>数据来源：<b>政策标准中心（协会收录入库）</b></span><span>状态口径：<b>草稿 / 待审核 / 已发布 / 已退回</b></span><span>原文以发布单位官方渠道为准</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="createOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="createOpen = false"><form class="panel modal-card" @submit.prevent="createPolicy"><div class="modal-head"><div><span class="eyebrow">POLICY COLLECTION</span><h2>收录政策</h2></div><button type="button" class="icon-button" @click="createOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>标题 *</span><input v-model="form.title" required maxlength="300" /></label><label><span>发布单位</span><input v-model="form.authority" /></label><label><span>文号</span><input v-model="form.documentNumber" /></label><label><span>级别</span><input v-model="form.level" placeholder="国家/北京市/行业协会" /></label><label><span>分类</span><input v-model="form.category" /></label><label><span>发布日期</span><input v-model="form.publishDate" type="date" /></label><label><span>施行日期</span><input v-model="form.effectiveDate" type="date" /></label><label class="form-span-2"><span>来源链接</span><input v-model="form.sourceUrl" type="url" /></label><label class="form-span-2"><span>摘要</span><textarea v-model="form.summary" rows="5" /></label><label><span>标签</span><textarea v-model="form.tags" rows="3" /></label><label><span>可见范围</span><select v-model="form.visibility"><option value="ASSOCIATION">本协会</option><option value="MEMBERS">会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="createOpen = false">取消</button><button class="primary-button" :disabled="busy">保存草稿</button></div></form></div>
    <div v-if="selected" class="modal-backdrop" role="dialog" aria-modal="true" :aria-label="`政策详情：${selected.title}`" @click.self="selected = null"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">POLICY DETAIL</span><h2>{{ selected.title }}</h2></div><button class="icon-button" aria-label="关闭政策详情" @click="selected = null">×</button></div><div class="detail-grid"><div><span>发布单位</span><strong>{{ selected.authority || '—' }}</strong></div><div><span>文号</span><strong>{{ selected.documentNumber || '—' }}</strong></div><div><span>状态</span><strong>{{ displayBusinessStatus(selected.status) }} · {{ effectiveState(selected) }}</strong></div><div><span>适用对象</span><strong>{{ visibilityLabel(selected.visibility) }}</strong></div><div><span>数据版本</span><strong>v{{ selected.version ?? '—' }}</strong></div><div><span>更新时间</span><strong>{{ formatDateTime(selected.updatedAt || selected.publishDate) }}</strong></div></div><div class="modal-copy"><p>{{ selected.summary || '暂无摘要' }}</p><a v-if="selected.sourceUrl" :href="selected.sourceUrl" target="_blank" rel="noopener noreferrer">打开原始来源 ↗</a><p v-else class="table-muted">该政策未登记外部原文链接，以协会入库版本为准。</p></div><div class="impact-inline"><h4>对企业的影响</h4><template v-if="impactsFor(selected).length"><div v-for="impact in impactsFor(selected)" :key="impact.id"><p><b>{{ impact.enterpriseName }}</b> · 影响等级：{{ impact.impactLevel }} · 分析方法：{{ impact.analysisMethod }}</p><p>{{ impact.summary }}</p><small>{{ formatDateTime(impact.updatedAt) }} · 分析状态：{{ displayBusinessStatus(impact.status) }}</small></div></template><p v-else>暂无与该政策关联的影响分析。可先组织会员学习原文，并结合业务自行评估。</p><h4>建议动作</h4><ul class="advice-list"><li v-for="advice in adviceFor(selected)" :key="advice">{{ advice }}</li></ul></div><div class="form-actions"><button v-if="canWrite && ['DRAFT', 'REJECTED'].includes(selected.status)" class="secondary-button" @click="submit(selected)">提交审核</button><button v-if="canReview && selected.status === 'PENDING_REVIEW'" class="secondary-button" @click="review(selected, false)">退回修订</button><button v-if="canReview && selected.status === 'PENDING_REVIEW'" class="primary-button" @click="review(selected, true)">审核发布</button></div></section></div>
    <div v-if="subscriptionOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="subscriptionOpen = false"><section class="panel modal-card compact-modal"><div class="modal-head"><div><span class="eyebrow">POLICY SUBSCRIPTION</span><h2>政策订阅设置</h2></div><button class="icon-button" @click="subscriptionOpen = false">×</button></div><div class="modal-copy"><div v-for="item in subscriptions" :key="item.id" class="subscription-row"><div><strong>{{ item.subscriptionType }}</strong><span>{{ item.channels.join('、') }}</span></div><button class="secondary-button small" @click="toggleSubscription(item)">{{ item.status === 'ACTIVE' ? '暂停' : '恢复' }}</button></div><p v-if="!subscriptions.length">当前未订阅政策通知。</p></div><div class="form-actions"><button class="primary-button" :disabled="busy" @click="addSubscription">+ 开启站内政策通知</button></div></section></div>
    <div v-if="impactOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="impactOpen = false"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">POLICY IMPACT</span><h2>已归档影响分析</h2></div><button class="icon-button" @click="impactOpen = false">×</button></div><div class="impact-list"><article v-for="item in impacts" :key="item.id"><div><StatusBadge :value="displayBusinessStatus(item.status)" /><strong>{{ item.policyTitle }}</strong><span>{{ item.enterpriseName }} · {{ item.impactLevel }} · {{ item.analysisMethod }}</span></div><p>{{ item.summary }}</p><small>{{ formatDateTime(item.updatedAt) }}</small></article><div v-if="!impacts.length" class="empty-business-state"><b>暂无影响分析</b></div></div></section></div>
  </div>
</template>
