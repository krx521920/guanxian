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
const selected = ref<Policy | null>(null)
const createOpen = ref(false)
const subscriptionOpen = ref(false)
const impactOpen = ref(false)
const qaQuestion = ref('')
const qaAnswer = ref<PolicyQuestionAnswer | null>(null)
const qaBusy = ref(false)
const qaError = ref('')
const levels = computed(() => ['全部', ...new Set(items.value.map((item) => item.level).filter(Boolean))])
const canWrite = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const affectedEnterprises = computed(() => new Set(impacts.value.map((item) => item.enterpriseId)).size)
const filtered = computed(() => items.value.filter((item) => {
  const text = `${item.title}${item.authority}${item.summary || ''}${(item.tags || []).join('')}`
  return (activeLevel.value === '全部' || item.level === activeLevel.value) && (!keyword.value || text.includes(keyword.value))
}))
const form = reactive({ title: '', authority: '', documentNumber: '', level: '', category: '', publishDate: '', effectiveDate: '', sourceUrl: '', summary: '', tags: '', visibility: 'MEMBERS' })
let searchTimer: number | null = null

async function load() {
  loading.value = true; error.value = null
  try {
    const [policies, impactPage] = await Promise.all([platformApi.policies(keyword.value.trim(), page.value, size.value), platformApi.policyImpacts()])
    items.value = policies.items; total.value = policies.total; page.value = policies.page; size.value = policies.size; impacts.value = impactPage.items
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

watch(keyword, () => {
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
    <section class="panel policy-qa"><div><span class="eyebrow">CITED POLICY Q&amp;A</span><h2>带出处的政策问答</h2><p>回答仅依据当前身份可见且已入库的资料；每条结论都附带片段出处和追踪编号。</p></div><form class="modal-copy" @submit.prevent="askKnowledge"><label><span>请输入政策、标准或协会资料问题</span><textarea v-model="qaQuestion" rows="3" maxlength="2000" placeholder="例如：资料中对地下管线安全监测提出了哪些要求？" required /></label><div class="form-actions"><button class="primary-button" :disabled="qaBusy || !qaQuestion.trim()">{{ qaBusy ? '检索生成中…' : '查询资料' }}</button></div></form><div v-if="qaError" class="save-message" role="alert">{{ qaError }}</div><article v-if="qaAnswer" class="modal-copy"><div class="policy-meta"><StatusBadge :value="qaAnswer.retrievalMode === 'HYBRID_VECTOR' ? '混合向量检索' : '关键词检索'" /><span>追踪编号 {{ qaAnswer.traceId }}</span></div><p>{{ qaAnswer.answer }}</p><div v-if="qaAnswer.citations.length" class="impact-list"><article v-for="citation in qaAnswer.citations" :key="citation.chunkId"><div><strong>[{{ citation.order }}] {{ citation.documentName }}</strong><span>版本 {{ citation.version }} · 片段 {{ citation.chunkIndex + 1 }} · 相关度 {{ citation.score.toFixed(3) }}</span></div><p>{{ citation.quote }}</p><a v-if="citation.source" :href="citation.source" target="_blank" rel="noopener noreferrer">查看外部原始来源 ↗</a><button v-else-if="citation.sourceAttachmentId" class="text-button" type="button" :disabled="qaBusy" @click="downloadCitationSource(citation)">下载原始附件 ↓</button><span v-else>该资料未登记外部链接或原始附件。</span></article></div><p v-else>当前可见资料未检索到足够证据，系统没有生成无出处答案。</p></article></section>
    <section class="panel filter-panel policy-filter"><div class="segmented"><button v-for="level in levels" :key="level" :class="{ active: activeLevel === level }" @click="activeLevel = level">{{ level }}</button></div><div class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索政策标题、发布单位或关键词" /></div></section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="policy-list">
      <article v-for="policy in filtered" :key="policy.id" class="policy-card panel"><div class="policy-level">{{ policy.level || '未分级' }}</div><div class="policy-body"><div class="policy-meta"><StatusBadge :value="displayBusinessStatus(policy.status)" /><span>{{ policy.category || '未分类' }}</span><span>发布于 {{ policy.publishDate || '未公布' }}</span></div><h2>{{ policy.title }}</h2><p>{{ policy.summary || '暂无摘要' }}</p><div class="tags"><span v-for="tag in policy.tags" :key="tag">{{ tag }}</span></div></div><div class="policy-side"><span>发布单位</span><strong>{{ policy.authority || '—' }}</strong><span>施行日期</span><strong>{{ displayEffectiveDate(policy.effectiveDate) }}</strong><button class="text-button" @click="selected = policy">查看详情 →</button></div></article>
      <div v-if="!filtered.length" class="panel empty-business-state"><b>暂无符合条件的政策</b><span>可调整筛选条件，或由协会工作人员收录真实政策。</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false"><form class="panel modal-card" @submit.prevent="createPolicy"><div class="modal-head"><div><span class="eyebrow">POLICY COLLECTION</span><h2>收录政策</h2></div><button type="button" class="icon-button" @click="createOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>标题 *</span><input v-model="form.title" required maxlength="300" /></label><label><span>发布单位</span><input v-model="form.authority" /></label><label><span>文号</span><input v-model="form.documentNumber" /></label><label><span>级别</span><input v-model="form.level" placeholder="国家/北京市/行业协会" /></label><label><span>分类</span><input v-model="form.category" /></label><label><span>发布日期</span><input v-model="form.publishDate" type="date" /></label><label><span>施行日期</span><input v-model="form.effectiveDate" type="date" /></label><label class="form-span-2"><span>来源链接</span><input v-model="form.sourceUrl" type="url" /></label><label class="form-span-2"><span>摘要</span><textarea v-model="form.summary" rows="5" /></label><label><span>标签</span><textarea v-model="form.tags" rows="3" /></label><label><span>可见范围</span><select v-model="form.visibility"><option value="ASSOCIATION">本协会</option><option value="MEMBERS">会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="createOpen = false">取消</button><button class="primary-button" :disabled="busy">保存草稿</button></div></form></div>
    <div v-if="selected" class="modal-backdrop" @click.self="selected = null"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">POLICY DETAIL</span><h2>{{ selected.title }}</h2></div><button class="icon-button" @click="selected = null">×</button></div><div class="detail-grid"><div><span>发布单位</span><strong>{{ selected.authority || '—' }}</strong></div><div><span>文号</span><strong>{{ selected.documentNumber || '—' }}</strong></div><div><span>状态</span><strong>{{ displayBusinessStatus(selected.status) }}</strong></div><div><span>数据版本</span><strong>{{ selected.version ?? '—' }}</strong></div></div><div class="modal-copy"><p>{{ selected.summary || '暂无摘要' }}</p><a v-if="selected.sourceUrl" :href="selected.sourceUrl" target="_blank" rel="noopener noreferrer">打开原始来源 ↗</a></div><div class="form-actions"><button v-if="canWrite && ['DRAFT', 'REJECTED'].includes(selected.status)" class="secondary-button" @click="submit(selected)">提交审核</button><button v-if="canReview && selected.status === 'PENDING_REVIEW'" class="secondary-button" @click="review(selected, false)">退回修订</button><button v-if="canReview && selected.status === 'PENDING_REVIEW'" class="primary-button" @click="review(selected, true)">审核发布</button></div></section></div>
    <div v-if="subscriptionOpen" class="modal-backdrop" @click.self="subscriptionOpen = false"><section class="panel modal-card compact-modal"><div class="modal-head"><div><span class="eyebrow">POLICY SUBSCRIPTION</span><h2>政策订阅设置</h2></div><button class="icon-button" @click="subscriptionOpen = false">×</button></div><div class="modal-copy"><div v-for="item in subscriptions" :key="item.id" class="subscription-row"><div><strong>{{ item.subscriptionType }}</strong><span>{{ item.channels.join('、') }}</span></div><button class="secondary-button small" @click="toggleSubscription(item)">{{ item.status === 'ACTIVE' ? '暂停' : '恢复' }}</button></div><p v-if="!subscriptions.length">当前未订阅政策通知。</p></div><div class="form-actions"><button class="primary-button" :disabled="busy" @click="addSubscription">+ 开启站内政策通知</button></div></section></div>
    <div v-if="impactOpen" class="modal-backdrop" @click.self="impactOpen = false"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">POLICY IMPACT</span><h2>已归档影响分析</h2></div><button class="icon-button" @click="impactOpen = false">×</button></div><div class="impact-list"><article v-for="item in impacts" :key="item.id"><div><StatusBadge :value="displayBusinessStatus(item.status)" /><strong>{{ item.policyTitle }}</strong><span>{{ item.enterpriseName }} · {{ item.impactLevel }} · {{ item.analysisMethod }}</span></div><p>{{ item.summary }}</p><small>{{ formatDateTime(item.updatedAt) }}</small></article><div v-if="!impacts.length" class="empty-business-state"><b>暂无影响分析</b></div></div></section></div>
  </div>
</template>
