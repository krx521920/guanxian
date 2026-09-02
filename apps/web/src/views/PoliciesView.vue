<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { createLatestRequestGate } from '../services/latest-request'
import { platformApi } from '../services/platform-api'
import type { MemberEnterprise, Policy, PolicyHistory, PolicyImpactAnalysis, PolicyImpactHistory, PolicyQuestionAnswer, PolicyUpsertPayload, Subscription } from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText, splitItems } from './business-form'
import { displayEffectiveDate, safeExternalUrl } from './policy-display'

const auth = useAuth()
const route = useRoute()
const items = ref<Policy[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const includeDeleted = ref(false)
const impacts = ref<PolicyImpactAnalysis[]>([])
const impactTotal = ref(0)
const impactAllTotal = ref(0)
const impactPageIndex = ref(0)
const impactPageSize = ref(20)
const impactError = ref('')
const impactLoading = ref(true)
const impactStatus = ref('')
const impactSelected = ref<PolicyImpactAnalysis | null>(null)
const impactHistories = ref<PolicyImpactHistory[]>([])
const impactHistoryError = ref('')
const impactDetailError = ref('')
const impactDetailLoading = ref(false)
const impactReviewComment = ref('')
const impactBusy = ref(false)
const impactCreateOpen = ref(false)
const impactPolicy = ref<Policy | null>(null)
const impactMembers = ref<MemberEnterprise[]>([])
const impactMemberPage = ref(0)
const impactMemberSize = ref(20)
const impactMemberTotal = ref(0)
const impactMemberQuery = ref('')
const impactMemberError = ref('')
const impactMemberLoading = ref(false)
const impactEnterpriseId = ref('')
const subscriptions = ref<Subscription[]>([])
const subscriptionError = ref('')
const histories = ref<PolicyHistory[]>([])
const historyError = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const keyword = ref('')
const activeLevel = ref('')
const policyLevels = ref<string[]>([])
const selected = ref<Policy | null>(null)
const editing = ref<Policy | null>(null)
const editorOpen = ref(false)
const historyOpen = ref(false)
const subscriptionOpen = ref(false)
const impactOpen = ref(false)
const qaQuestion = ref('')
const qaAnswer = ref<PolicyQuestionAnswer | null>(null)
const qaBusy = ref(false)
const qaError = ref('')
const canWrite = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const hasAssociationContext = computed(() => auth.user.value?.role !== 'SYSTEM_ADMIN' || Boolean(auth.user.value?.associationId))
const canWriteHere = computed(() => canWrite.value && hasAssociationContext.value)
const canReviewHere = computed(() => canReview.value && hasAssociationContext.value)
const canManageDeleted = computed(() => canWriteHere.value)
const hasSubscriptionContext = computed(() => auth.user.value?.role !== 'SYSTEM_ADMIN' || Boolean(auth.user.value?.associationId))
const affectedEnterprises = computed(() => new Set(impacts.value.map((item) => item.enterpriseId)).size)
const policySubscription = computed(() => subscriptions.value.find((item) => item.subscriptionType === 'POLICY'))
const form = reactive({ title: '', authority: '', documentNumber: '', level: '', category: '', publishDate: '', effectiveDate: '', sourceUrl: '', summary: '', tags: '', visibility: 'MEMBERS' })
let searchTimer: number | null = null
let memberSearchTimer: number | null = null
const policyListRequestGate = createLatestRequestGate()
const impactListRequestGate = createLatestRequestGate()
const impactDetailRequestGate = createLatestRequestGate()

async function load() {
  const requestEpoch = policyListRequestGate.begin()
  const requestedKeyword = keyword.value.trim()
  const requestedLevel = activeLevel.value
  const requestedSize = size.value
  const requestedIncludeDeleted = canManageDeleted.value && includeDeleted.value
  let requestedPage = page.value
  loading.value = true
  error.value = null
  try {
    let [policies, visibleLevels] = await Promise.all([
      platformApi.policies(
        requestedKeyword, requestedPage, requestedSize, requestedIncludeDeleted, requestedLevel,
      ),
      platformApi.policyLevels(),
    ])
    if (!policyListRequestGate.isCurrent(requestEpoch)) return
    if (!policies.items.length && policies.total > 0 && policies.page > 0) {
      requestedPage = Math.max(0, Math.ceil(policies.total / policies.size) - 1)
      policies = await platformApi.policies(
        requestedKeyword, requestedPage, requestedSize, requestedIncludeDeleted, requestedLevel,
      )
      if (!policyListRequestGate.isCurrent(requestEpoch)) return
    }
    items.value = policies.items
    total.value = policies.total
    page.value = policies.page
    size.value = policies.size
    policyLevels.value = visibleLevels
  } catch (reason) {
    if (policyListRequestGate.isCurrent(requestEpoch)) {
      error.value = safePageResourceError(reason)
    }
  } finally {
    if (policyListRequestGate.isCurrent(requestEpoch)) {
      loading.value = false
    }
  }
}

async function loadImpacts() {
  const requestEpoch = impactListRequestGate.begin()
  const requestedStatus = impactStatus.value
  const requestedSize = impactPageSize.value
  let requestedPage = impactPageIndex.value
  impactLoading.value = true
  impactError.value = ''
  try {
    let impactPage = await platformApi.policyImpacts(
      requestedPage,
      requestedSize,
      { status: requestedStatus || undefined },
    )
    if (!impactListRequestGate.isCurrent(requestEpoch)) return
    if (!impactPage.items.length && impactPage.total > 0 && impactPage.page > 0) {
      requestedPage = Math.max(0, Math.ceil(impactPage.total / impactPage.size) - 1)
      impactPage = await platformApi.policyImpacts(
        requestedPage,
        requestedSize,
        { status: requestedStatus || undefined },
      )
      if (!impactListRequestGate.isCurrent(requestEpoch)) return
    }
    impacts.value = impactPage.items
    impactTotal.value = impactPage.total
    if (!requestedStatus) impactAllTotal.value = impactPage.total
    impactPageIndex.value = impactPage.page
    impactPageSize.value = impactPage.size
  } catch (reason) {
    if (impactListRequestGate.isCurrent(requestEpoch)) {
      impacts.value = []
      impactTotal.value = 0
      impactError.value = apiActionMessage(reason, '政策影响分析暂时无法加载。')
    }
  } finally {
    if (impactListRequestGate.isCurrent(requestEpoch)) {
      impactLoading.value = false
    }
  }
}

function payload(): PolicyUpsertPayload {
  return { associationId: auth.user.value?.associationId || null, title: form.title.trim(), authority: nullableText(form.authority), documentNumber: nullableText(form.documentNumber), level: nullableText(form.level), category: nullableText(form.category), publishDate: form.publishDate || null, effectiveDate: form.effectiveDate || null, sourceUrl: nullableText(form.sourceUrl), summary: nullableText(form.summary), tags: splitItems(form.tags), visibility: form.visibility }
}

function resetForm(item?: Policy) {
  Object.assign(form, {
    title: item?.title || '', authority: item?.authority || '', documentNumber: item?.documentNumber || '',
    level: item?.level || '', category: item?.category || '', publishDate: item?.publishDate || '',
    effectiveDate: item?.effectiveDate || '', sourceUrl: item?.sourceUrl || '', summary: item?.summary || '',
    tags: (item?.tags || []).join('\n'), visibility: item?.visibility || 'MEMBERS',
  })
}

function openCreate() {
  editing.value = null
  resetForm()
  editorOpen.value = true
}

function openEdit(item: Policy) {
  editing.value = item
  resetForm(item)
  selected.value = null
  editorOpen.value = true
}

async function savePolicy() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try {
    const saved = editing.value
      ? await platformApi.updatePolicy(editing.value.id, payload(), editing.value.version || 0)
      : await platformApi.createPolicy(payload())
    if (!editing.value) page.value = 0
    await load()
    selected.value = editing.value ? saved : null
    editorOpen.value = false
    editing.value = null
    message.value = saved.version === 0 ? '政策已保存为草稿，核对后可提交审核。' : '政策内容已按最新版本保存。'
  } catch (reason) {
    message.value = apiActionMessage(reason, editing.value ? '政策编辑失败，请重新加载后再试。' : '政策收录失败，请检查必填项。')
  } finally {
    busy.value = false
  }
}

async function submit(item: Policy) {
  if (item.version === undefined || busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.submitPolicy(item.id, item.version); await load(); selected.value = saved; message.value = '政策已提交审核。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策提交失败。') }
  finally { busy.value = false }
}

async function review(item: Policy, approved: boolean) {
  if (item.version === undefined || busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.reviewPolicy(item.id, item.version, approved, approved ? '' : '请核对来源和摘要后重新提交'); await load(); selected.value = saved; message.value = approved ? '政策已审核发布；如需提醒订阅用户，请点击“发布站内通知”。' : '政策已退回修订。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策审核失败。') }
  finally { busy.value = false }
}

async function disablePolicy(item: Policy) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.disablePolicy(item); await load(); selected.value = saved; message.value = '政策已停用，不再参与正常展示和通知发布。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策停用失败。') }
  finally { busy.value = false }
}

async function deletePolicy(item: Policy) {
  if (busy.value || !window.confirm(`确认删除政策《${item.title}》？删除后仅管理视图可见，并可恢复。`)) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.deletePolicy(item); await load(); selected.value = includeDeleted.value ? saved : null; message.value = '政策已软删除，可在“包含已删除”管理视图中恢复。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策删除失败。') }
  finally { busy.value = false }
}

async function restorePolicy(item: Policy) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try { const saved = await platformApi.restorePolicy(item); await load(); selected.value = saved; message.value = '政策已恢复为草稿，请重新核对并提交审核。' }
  catch (reason) { message.value = apiActionMessage(reason, '政策恢复失败。') }
  finally { busy.value = false }
}

async function publishNotification(item: Policy) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const result = await platformApi.publishPolicyNotification(item)
    message.value = result.duplicate
      ? `该政策当前版本的站内通知已发布过，未重复发送；原投递人数 ${result.recipientCount} 人。`
      : `政策站内通知已发布，实际投递 ${result.recipientCount} 人。`
  } catch (reason) { message.value = apiActionMessage(reason, '政策通知发布失败，可稍后在政策详情中重试。') }
  finally { busy.value = false }
}

async function openHistory(item: Policy) {
  selected.value = null
  historyOpen.value = true; histories.value = []; historyError.value = ''; busy.value = true
  try { histories.value = await platformApi.policyHistory(item.id) }
  catch (reason) { historyError.value = apiActionMessage(reason, '政策历史加载失败。') }
  finally { busy.value = false }
}

async function openPolicy(item: Policy) {
  message.value = ''
  try { selected.value = await platformApi.policy(item.id, canManageDeleted.value && includeDeleted.value) }
  catch (reason) { message.value = apiActionMessage(reason, '政策详情加载失败或已不在当前可见范围。') }
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }
function toggleDeletedView() { page.value = 0; void load() }
function changeImpactPage(value: number) { impactPageIndex.value = value; void loadImpacts() }
function resizeImpactPage(value: number) { impactPageSize.value = value; impactPageIndex.value = 0; void loadImpacts() }
function filterImpacts() {
  impactPageIndex.value = 0
  closeImpactDetail()
  void loadImpacts()
}

async function loadImpactMembers() {
  impactMemberLoading.value = true
  impactMemberError.value = ''
  try {
    let members = await platformApi.members(
      impactMemberQuery.value.trim(), 'ACTIVE', impactMemberPage.value, impactMemberSize.value, false,
    )
    if (!members.items.length && members.total > 0 && members.page > 0) {
      impactMemberPage.value = Math.max(0, Math.ceil(members.total / members.size) - 1)
      members = await platformApi.members(
        impactMemberQuery.value.trim(), 'ACTIVE', impactMemberPage.value, impactMemberSize.value, false,
      )
    }
    impactMembers.value = members.items
    impactMemberTotal.value = members.total
    impactMemberPage.value = members.page
    impactMemberSize.value = members.size
    if (impactEnterpriseId.value && !members.items.some((item) => item.id === impactEnterpriseId.value)) {
      impactEnterpriseId.value = ''
    }
  } catch (reason) {
    impactMembers.value = []
    impactMemberTotal.value = 0
    impactMemberError.value = apiActionMessage(reason, '可分析企业加载失败。')
  } finally {
    impactMemberLoading.value = false
  }
}

function changeImpactMemberPage(value: number) { impactMemberPage.value = value; impactEnterpriseId.value = ''; void loadImpactMembers() }
function resizeImpactMemberPage(value: number) { impactMemberSize.value = value; impactMemberPage.value = 0; impactEnterpriseId.value = ''; void loadImpactMembers() }

function openImpactCreate(item: Policy) {
  impactPolicy.value = item
  impactEnterpriseId.value = ''
  impactMemberQuery.value = ''
  impactMemberPage.value = 0
  impactMemberError.value = ''
  selected.value = null
  impactCreateOpen.value = true
  void loadImpactMembers()
}

async function createImpact() {
  if (!impactPolicy.value || !impactEnterpriseId.value || impactBusy.value) return
  impactBusy.value = true
  impactMemberError.value = ''
  try {
    const created = await platformApi.createPolicyImpact(impactPolicy.value.id, impactEnterpriseId.value)
    impactCreateOpen.value = false
    impactPolicy.value = null
    impactStatus.value = ''
    impactPageIndex.value = 0
    await loadImpacts()
    message.value = '企业政策影响分析已生成并进入待审核状态。'
    await openImpact(created)
  } catch (reason) {
    impactMemberError.value = apiActionMessage(
      reason,
      '影响分析生成失败。请确认政策已发布、企业处于正常状态，并已入库与该政策关联的资料证据。',
    )
  } finally {
    impactBusy.value = false
  }
}

async function loadImpactHistory(id: string, requestEpoch: number) {
  try {
    const history = await platformApi.policyImpactHistory(id)
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value && impactSelected.value?.id === id) {
      impactHistories.value = history
    }
  } catch (reason) {
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value && impactSelected.value?.id === id) {
      impactHistories.value = []
      impactHistoryError.value = apiActionMessage(reason, '影响分析历史加载失败。')
    }
  }
}

function clearImpactDetailState() {
  impactSelected.value = null
  impactHistories.value = []
  impactHistoryError.value = ''
  impactReviewComment.value = ''
}

async function openImpact(item: PolicyImpactAnalysis) {
  const requestEpoch = impactDetailRequestGate.begin()
  impactOpen.value = true
  clearImpactDetailState()
  impactDetailError.value = ''
  impactDetailLoading.value = true
  try {
    const detail = await platformApi.policyImpact(item.id)
    if (!impactDetailRequestGate.isCurrent(requestEpoch) || !impactOpen.value) return
    impactSelected.value = detail
    await loadImpactHistory(detail.id, requestEpoch)
  } catch (reason) {
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
      clearImpactDetailState()
      impactDetailError.value = apiActionMessage(reason, '影响分析详情加载失败或已不在当前可见范围。')
    }
  } finally {
    if (impactDetailRequestGate.isCurrent(requestEpoch)) {
      impactDetailLoading.value = false
    }
  }
}

async function reanalyzeImpact() {
  if (!impactSelected.value || impactBusy.value) return
  const currentImpact = impactSelected.value
  const requestEpoch = impactDetailRequestGate.begin()
  impactBusy.value = true
  impactDetailError.value = ''
  try {
    const saved = await platformApi.reanalyzePolicyImpact(currentImpact)
    const listRefresh = loadImpacts()
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
      impactSelected.value = saved
      impactReviewComment.value = ''
      await Promise.all([loadImpactHistory(saved.id, requestEpoch), listRefresh])
      if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
        message.value = '已基于当前资料重新分析，结果进入待审核状态。'
      }
    } else {
      await listRefresh
    }
  } catch (reason) {
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
      clearImpactDetailState()
      impactDetailError.value = apiActionMessage(reason, '重新分析失败，请返回列表并重新加载最新详情。')
    }
  } finally {
    if (impactDetailRequestGate.isCurrent(requestEpoch)) {
      impactBusy.value = false
    }
  }
}

async function reviewImpact(approved: boolean) {
  if (!impactSelected.value || impactSelected.value.status !== 'PENDING_REVIEW' || impactBusy.value) return
  const currentImpact = impactSelected.value
  const requestEpoch = impactDetailRequestGate.begin()
  impactBusy.value = true
  impactDetailError.value = ''
  try {
    const saved = await platformApi.reviewPolicyImpact(
      currentImpact, approved, impactReviewComment.value,
    )
    const listRefresh = loadImpacts()
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
      impactSelected.value = saved
      await Promise.all([loadImpactHistory(saved.id, requestEpoch), listRefresh])
      if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
        message.value = approved ? '影响分析已审核通过。' : '影响分析已退回，可在资料更新后重新分析。'
      }
    } else {
      await listRefresh
    }
  } catch (reason) {
    if (impactDetailRequestGate.isCurrent(requestEpoch) && impactOpen.value) {
      clearImpactDetailState()
      impactDetailError.value = apiActionMessage(reason, '影响分析审核失败，请返回列表并重新加载最新详情。')
    }
  } finally {
    if (impactDetailRequestGate.isCurrent(requestEpoch)) {
      impactBusy.value = false
    }
  }
}

function closeImpactDetail() {
  impactDetailRequestGate.invalidate()
  clearImpactDetailState()
  impactDetailError.value = ''
  impactDetailLoading.value = false
  impactBusy.value = false
}

watch([keyword, activeLevel], () => {
  policyListRequestGate.invalidate()
  loading.value = true
  error.value = null
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { page.value = 0; void load() }, 300)
})
watch(impactMemberQuery, () => {
  if (!impactCreateOpen.value) return
  if (memberSearchTimer !== null) window.clearTimeout(memberSearchTimer)
  memberSearchTimer = window.setTimeout(() => {
    impactMemberPage.value = 0
    impactEnterpriseId.value = ''
    void loadImpactMembers()
  }, 300)
})
onBeforeUnmount(() => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  if (memberSearchTimer !== null) window.clearTimeout(memberSearchTimer)
  policyListRequestGate.invalidate()
  impactListRequestGate.invalidate()
  impactDetailRequestGate.invalidate()
})

function subscriptionSupported(item: Subscription) {
  return item.subscriptionType === 'POLICY'
    && Object.keys(item.filters || {}).length === 0
    && item.channels.length === 1
    && item.channels[0] === 'IN_APP'
}

async function openSubscriptions() {
  subscriptionOpen.value = true; busy.value = true; subscriptionError.value = ''; message.value = ''
  try { subscriptions.value = await platformApi.subscriptions() }
  catch (reason) { subscriptionError.value = apiActionMessage(reason, '订阅设置加载失败。') }
  finally { busy.value = false }
}

async function addSubscription() {
  if (busy.value || policySubscription.value || !hasSubscriptionContext.value) return
  busy.value = true; subscriptionError.value = ''
  try { subscriptions.value = [await platformApi.createSubscription({ subscriptionType: 'POLICY', filters: {}, channels: ['IN_APP'] }), ...subscriptions.value]; message.value = '已开启全部政策的站内通知。' }
  catch (reason) { subscriptionError.value = apiActionMessage(reason, '政策订阅创建失败。') }
  finally { busy.value = false }
}

async function repairSubscription(item: Subscription) {
  if (busy.value) return
  busy.value = true; subscriptionError.value = ''
  try { const saved = await platformApi.updateSubscription(item, { subscriptionType: 'POLICY', filters: {}, channels: ['IN_APP'] }); subscriptions.value = subscriptions.value.map((value) => value.id === saved.id ? saved : value); message.value = '历史订阅已修复为全部政策站内通知。' }
  catch (reason) { subscriptionError.value = apiActionMessage(reason, '历史订阅修复失败。') }
  finally { busy.value = false }
}

async function toggleSubscription(item: Subscription) {
  if (busy.value || !subscriptionSupported(item)) return
  busy.value = true; subscriptionError.value = ''
  try { const saved = await platformApi.toggleSubscription(item); subscriptions.value = subscriptions.value.map((value) => value.id === saved.id ? saved : value); message.value = saved.status === 'ACTIVE' ? '已恢复全部政策站内通知。' : '已暂停政策站内通知。' }
  catch (reason) { subscriptionError.value = apiActionMessage(reason, '订阅状态更新失败。') }
  finally { busy.value = false }
}

async function askKnowledge() {
  if (!qaQuestion.value.trim() || qaBusy.value) return
  qaBusy.value = true; qaError.value = ''; qaAnswer.value = null
  try {
    const answer = await platformApi.askPolicyQuestion(
      qaQuestion.value.trim(),
      5,
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
    qaAnswer.value = {
      ...answer,
      citations: answer.citations.map((citation) => ({
        ...citation,
        source: safeExternalUrl(citation.source),
      })),
    }
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

onMounted(async () => {
  void loadImpacts()
  await load()
  const policyId = typeof route.query.policyId === 'string' ? route.query.policyId : ''
  if (!policyId) return
  try { selected.value = await platformApi.policy(policyId, canManageDeleted.value && includeDeleted.value) }
  catch (reason) { message.value = apiActionMessage(reason, '通知关联的政策当前不可见或已被删除。') }
})
</script>

<template>
  <div>
    <PageHeader eyebrow="POLICY & STANDARD" title="政策标准" description="政策从收录、审核、发布到企业影响分析的真实数据闭环">
      <button class="secondary-button" type="button" @click="openSubscriptions">政策订阅设置</button>
      <button v-if="canWrite" class="primary-button" type="button" :disabled="!canWriteHere" @click="openCreate">+ 收录政策</button>
    </PageHeader>
    <div v-if="canWrite && !canWriteHere" class="save-message page-message">系统管理员需先在左侧选择协会，才能维护政策或订阅。</div>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="policy-hero panel">
      <div class="ai-orb">资料</div>
      <div>
        <span class="eyebrow">已建档影响分析</span>
        <h2 v-if="impactLoading">正在加载影响分析…</h2>
        <h2 v-else-if="!impactError">共 {{ impactAllTotal }} 项分析；当前加载页涉及 {{ affectedEnterprises }} 家企业</h2>
        <h2 v-else>影响分析暂时不可用</h2>
        <p v-if="impactLoading">正在读取当前身份可见的真实分析记录。</p>
        <p v-else-if="impactError">{{ impactError }} 政策列表仍可正常使用。</p>
        <p v-else-if="impactAllTotal">数字来自政策影响分析数据库；详情列出当前加载的真实记录。</p>
        <p v-else>暂无已建档分析，页面不会用模拟数字填充。</p>
      </div>
      <button class="secondary-button" type="button" @click="impactOpen = true">查看影响分析 →</button>
    </section>
    <section class="panel policy-qa"><div><span class="eyebrow">CITED POLICY Q&amp;A</span><h2>带出处的政策问答</h2><p>回答仅依据当前身份可见且已入库的资料；每条结论都附带片段出处和追踪编号。</p></div><form class="modal-copy" @submit.prevent="askKnowledge"><label><span>请输入政策、标准或协会资料问题</span><textarea v-model="qaQuestion" rows="3" maxlength="2000" placeholder="例如：资料中对地下管线安全监测提出了哪些要求？" required /></label><div class="form-actions"><button class="primary-button" :disabled="qaBusy || !qaQuestion.trim()">{{ qaBusy ? '检索生成中…' : '查询资料' }}</button></div></form><div v-if="qaError" class="save-message" role="alert">{{ qaError }}</div><article v-if="qaAnswer" class="modal-copy"><div class="policy-meta"><StatusBadge :value="qaAnswer.retrievalMode === 'HYBRID_VECTOR' ? '混合向量检索' : '关键词检索'" /><span>追踪编号 {{ qaAnswer.traceId }}</span></div><p>{{ qaAnswer.answer }}</p><div v-if="qaAnswer.citations.length" class="impact-list"><article v-for="citation in qaAnswer.citations" :key="citation.chunkId"><div><strong>[{{ citation.order }}] {{ citation.documentName }}</strong><span>版本 {{ citation.version }} · 片段 {{ citation.chunkIndex + 1 }} · 相关度 {{ citation.score.toFixed(3) }}</span></div><p>{{ citation.quote }}</p><a v-if="citation.source" :href="citation.source" target="_blank" rel="noopener noreferrer">查看外部原始来源 ↗</a><button v-else-if="citation.sourceAttachmentId" class="text-button" type="button" :disabled="qaBusy" @click="downloadCitationSource(citation)">下载原始附件 ↓</button><span v-else>该资料未登记外部链接或原始附件。</span></article></div><p v-else>当前可见资料未检索到足够证据，系统没有生成无出处答案。</p></article></section>
    <section class="panel filter-panel policy-filter">
      <div class="segmented" aria-label="政策级别筛选">
        <button type="button" :class="{ active: activeLevel === '' }" @click="activeLevel = ''">全部级别</button>
        <button v-for="level in policyLevels" :key="level" type="button" :class="{ active: activeLevel === level }" @click="activeLevel = level">{{ level }}</button>
      </div>
      <div class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索政策标题、发布单位或关键词" /></div>
      <label v-if="canManageDeleted" class="policy-deleted-toggle"><input v-model="includeDeleted" type="checkbox" @change="toggleDeletedView" /> 包含已删除政策</label>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="policy-list">
      <article v-for="policy in items" :key="policy.id" class="policy-card panel" :class="{ 'policy-deleted': policy.deleted }"><div class="policy-level">{{ policy.level || '未分级' }}</div><div class="policy-body"><div class="policy-meta"><StatusBadge :value="policy.deleted ? '已删除' : displayBusinessStatus(policy.status)" /><span>{{ policy.category || '未分类' }}</span><span>发布于 {{ policy.publishDate || '未公布' }}</span></div><h2>{{ policy.title }}</h2><p>{{ policy.summary || '暂无摘要' }}</p><div class="tags"><span v-for="tag in policy.tags" :key="tag">{{ tag }}</span></div></div><div class="policy-side"><span>发布单位</span><strong>{{ policy.authority || '—' }}</strong><span>施行日期</span><strong>{{ displayEffectiveDate(policy.effectiveDate) }}</strong><button class="text-button" type="button" @click="openPolicy(policy)">查看详情 →</button></div></article>
      <div v-if="!items.length" class="panel empty-business-state"><b>{{ includeDeleted ? '当前管理范围暂无政策记录' : '暂无符合条件的政策' }}</b><span>可调整搜索条件，或由协会工作人员收录真实政策。</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="editorOpen" class="modal-backdrop" @click.self="editorOpen = false"><form class="panel modal-card" @submit.prevent="savePolicy"><div class="modal-head"><div><span class="eyebrow">POLICY COLLECTION</span><h2>{{ editing ? '编辑政策' : '收录政策' }}</h2></div><button type="button" class="icon-button" @click="editorOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>标题 *</span><input v-model="form.title" required maxlength="300" /></label><label><span>发布单位</span><input v-model="form.authority" /></label><label><span>文号</span><input v-model="form.documentNumber" /></label><label><span>级别</span><input v-model="form.level" placeholder="国家/北京市/行业协会" /></label><label><span>分类</span><input v-model="form.category" /></label><label><span>发布日期</span><input v-model="form.publishDate" type="date" /></label><label><span>施行日期</span><input v-model="form.effectiveDate" type="date" /></label><label class="form-span-2"><span>来源链接</span><input v-model="form.sourceUrl" type="url" /></label><label class="form-span-2"><span>摘要</span><textarea v-model="form.summary" rows="5" /></label><label><span>标签（每行或逗号分隔）</span><textarea v-model="form.tags" rows="3" /></label><label><span>可见范围</span><select v-model="form.visibility"><option value="PRIVATE">仅协会内部</option><option value="MEMBERS">会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="editorOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ editing ? '保存修改' : '保存草稿' }}</button></div></form></div>

    <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
      <section class="panel modal-card">
        <div class="modal-head">
          <div><span class="eyebrow">POLICY DETAIL</span><h2>{{ selected.title }}</h2></div>
          <button type="button" class="icon-button" @click="selected = null">×</button>
        </div>
        <div class="detail-grid">
          <div><span>发布单位</span><strong>{{ selected.authority || '—' }}</strong></div>
          <div><span>文号</span><strong>{{ selected.documentNumber || '—' }}</strong></div>
          <div><span>状态</span><strong>{{ selected.deleted ? '已删除' : displayBusinessStatus(selected.status) }}</strong></div>
          <div><span>数据版本</span><strong>{{ selected.version ?? '—' }}</strong></div>
        </div>
        <div class="modal-copy">
          <p>{{ selected.summary || '暂无摘要' }}</p>
          <a v-if="safeExternalUrl(selected.sourceUrl)" :href="safeExternalUrl(selected.sourceUrl) || undefined" target="_blank" rel="noopener noreferrer">打开原始来源 ↗</a>
        </div>
        <div class="form-actions policy-actions">
          <button v-if="canWriteHere" class="secondary-button" type="button" :disabled="busy" @click="openHistory(selected)">历史版本</button>
          <button v-if="canWriteHere && !selected.deleted && ['DRAFT', 'REJECTED'].includes(selected.status)" class="secondary-button" type="button" :disabled="busy" @click="openEdit(selected)">编辑</button>
          <button v-if="canWriteHere && !selected.deleted && ['DRAFT', 'REJECTED'].includes(selected.status)" class="secondary-button" type="button" :disabled="busy" @click="submit(selected)">提交审核</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status === 'PENDING_REVIEW'" class="secondary-button" type="button" :disabled="busy" @click="review(selected, false)">退回修订</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status === 'PENDING_REVIEW'" class="primary-button" type="button" :disabled="busy" @click="review(selected, true)">审核发布</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status === 'PUBLISHED' && !selected.disabled" class="primary-button" type="button" :disabled="busy" @click="publishNotification(selected)">发布站内通知</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status === 'PUBLISHED' && !selected.disabled" class="secondary-button" type="button" :disabled="busy" @click="openImpactCreate(selected)">分析企业影响</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status !== 'DISABLED'" class="secondary-button" type="button" :disabled="busy" @click="disablePolicy(selected)">停用</button>
          <button v-if="canReviewHere && !selected.deleted && selected.status === 'DISABLED'" class="primary-button" type="button" :disabled="busy" @click="restorePolicy(selected)">恢复为草稿</button>
          <button v-if="canReviewHere && !selected.deleted" class="secondary-button danger-action" type="button" :disabled="busy" @click="deletePolicy(selected)">删除</button>
          <button v-if="canReviewHere && selected.deleted" class="primary-button" type="button" :disabled="busy" @click="restorePolicy(selected)">恢复为草稿</button>
        </div>
      </section>
    </div>

    <div v-if="historyOpen" class="modal-backdrop" @click.self="historyOpen = false"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">POLICY HISTORY</span><h2>政策历史版本</h2></div><button type="button" class="icon-button" @click="historyOpen = false">×</button></div><div v-if="historyError" class="save-message" role="alert">{{ historyError }}</div><div v-else-if="busy && !histories.length" class="empty-business-state"><b>正在加载历史…</b></div><div v-else class="policy-history-list"><article v-for="item in histories" :key="`${item.version}-${item.occurredAt}`"><div><strong>版本 {{ item.version }} · {{ item.action }}</strong><span>{{ item.actorSubject }} · {{ formatDateTime(item.occurredAt) }}</span></div><pre>{{ JSON.stringify(item.snapshot, null, 2) }}</pre></article><div v-if="!histories.length" class="empty-business-state"><b>暂无历史记录</b></div></div></section></div>

    <div v-if="subscriptionOpen" class="modal-backdrop" @click.self="subscriptionOpen = false"><section class="panel modal-card compact-modal"><div class="modal-head"><div><span class="eyebrow">POLICY SUBSCRIPTION</span><h2>政策订阅设置</h2></div><button type="button" class="icon-button" @click="subscriptionOpen = false">×</button></div><p>当前仅支持“全部政策 · 站内通知”。不支持的存量筛选不会参与投递。</p><div v-if="subscriptionError" class="save-message" role="alert">{{ subscriptionError }}</div><div class="modal-copy"><div v-for="item in subscriptions" :key="item.id" class="subscription-row"><div><strong>{{ subscriptionSupported(item) ? '全部政策站内通知' : `历史配置：${item.subscriptionType}` }}</strong><span>{{ item.status === 'ACTIVE' ? '已启用' : '已停用' }} · {{ item.channels.join('、') || '无渠道' }}</span></div><button v-if="subscriptionSupported(item)" class="secondary-button small" type="button" :disabled="busy" @click="toggleSubscription(item)">{{ item.status === 'ACTIVE' ? '暂停' : '恢复' }}</button><button v-else-if="item.subscriptionType === 'POLICY'" class="secondary-button small" type="button" :disabled="busy" @click="repairSubscription(item)">修复配置</button><span v-else>已隔离</span></div><p v-if="!subscriptions.length">当前未订阅政策通知。</p></div><div class="form-actions"><button class="primary-button" type="button" :disabled="busy || Boolean(policySubscription) || !hasSubscriptionContext" @click="addSubscription">{{ policySubscription ? '政策订阅已存在' : '+ 开启全部政策站内通知' }}</button></div></section></div>

    <div v-if="impactOpen" class="modal-backdrop" @click.self="impactOpen = false; closeImpactDetail()">
      <section class="panel modal-card">
        <div class="modal-head">
          <div>
            <span class="eyebrow">POLICY IMPACT</span>
            <h2>{{ impactSelected ? '影响分析详情' : `政策影响分析（共 ${impactTotal} 项）` }}</h2>
          </div>
          <div class="impact-head-actions">
            <button v-if="impactSelected" type="button" class="secondary-button small" @click="closeImpactDetail">返回列表</button>
            <button type="button" class="icon-button" @click="impactOpen = false; closeImpactDetail()">×</button>
          </div>
        </div>

        <div v-if="impactDetailLoading" class="empty-business-state"><b>正在加载影响分析详情…</b></div>

        <template v-else-if="impactSelected">
          <div v-if="impactDetailError" class="save-message" role="alert">{{ impactDetailError }}</div>
          <div class="detail-grid">
            <div><span>政策</span><strong>{{ impactSelected.policyTitle }}</strong></div>
            <div><span>企业</span><strong>{{ impactSelected.enterpriseName }}</strong></div>
            <div><span>影响等级</span><strong>{{ impactSelected.impactLevel }}</strong></div>
            <div><span>状态</span><strong>{{ displayBusinessStatus(impactSelected.status) }}</strong></div>
            <div><span>分析方法</span><strong>{{ impactSelected.analysisMethod }}</strong></div>
            <div><span>证据片段</span><strong>{{ impactSelected.evidenceChunkIds.length }} 条</strong></div>
            <div><span>数据版本</span><strong>{{ impactSelected.version }}</strong></div>
            <div><span>更新时间</span><strong>{{ formatDateTime(impactSelected.updatedAt) }}</strong></div>
          </div>
          <div class="modal-copy"><p>{{ impactSelected.summary }}</p></div>
          <label v-if="canReviewHere && impactSelected.status === 'PENDING_REVIEW'" class="modal-copy">
            <span>审核意见</span>
            <textarea v-model="impactReviewComment" rows="3" maxlength="1000" placeholder="可填写审核依据或退回原因" />
          </label>
          <div v-if="canReviewHere" class="form-actions policy-actions">
            <button class="secondary-button" type="button" :disabled="impactBusy" @click="reanalyzeImpact">重新分析</button>
            <button v-if="impactSelected.status === 'PENDING_REVIEW'" class="secondary-button" type="button" :disabled="impactBusy" @click="reviewImpact(false)">退回</button>
            <button v-if="impactSelected.status === 'PENDING_REVIEW'" class="primary-button" type="button" :disabled="impactBusy" @click="reviewImpact(true)">审核通过</button>
          </div>
          <section class="impact-history-section">
            <h3>操作历史</h3>
            <div v-if="impactHistoryError" class="save-message" role="alert">{{ impactHistoryError }}</div>
            <div v-else class="policy-history-list">
              <article v-for="item in impactHistories" :key="`${item.version}-${item.occurredAt}`">
                <div><strong>版本 {{ item.version }} · {{ item.action }}</strong><span>{{ item.actorSubject }} · {{ formatDateTime(item.occurredAt) }}</span></div>
                <pre>{{ JSON.stringify(item.snapshot, null, 2) }}</pre>
              </article>
              <div v-if="!impactHistories.length && !impactBusy" class="empty-business-state"><b>暂无历史记录</b></div>
            </div>
          </section>
        </template>

        <template v-else>
          <div v-if="impactDetailError" class="save-message" role="alert">{{ impactDetailError }}</div>
          <div class="filter-panel impact-filter">
            <label>
              <span>审核状态</span>
              <select v-model="impactStatus" @change="filterImpacts">
                <option value="">全部</option>
                <option value="PENDING_REVIEW">待审核</option>
                <option value="APPROVED">已通过</option>
                <option value="REJECTED">已退回</option>
              </select>
            </label>
          </div>
          <div v-if="impactLoading" class="empty-business-state"><b>正在加载影响分析…</b></div>
          <div v-else-if="impactError" class="empty-business-state">
            <b>{{ impactError }}</b>
            <button class="secondary-button" type="button" @click="loadImpacts">重新加载</button>
          </div>
          <div v-else class="impact-list">
            <article v-for="item in impacts" :key="item.id">
              <div><StatusBadge :value="displayBusinessStatus(item.status)" /><strong>{{ item.policyTitle }}</strong><span>{{ item.enterpriseName }} · {{ item.impactLevel }} · {{ item.analysisMethod }}</span></div>
              <p>{{ item.summary }}</p>
              <div class="impact-row-actions"><small>{{ formatDateTime(item.updatedAt) }}</small><button class="text-button" type="button" @click="openImpact(item)">查看详情 →</button></div>
            </article>
            <div v-if="!impacts.length" class="empty-business-state"><b>暂无影响分析</b><span v-if="canReviewHere">请从已发布政策详情中选择企业并生成分析。</span></div>
            <PaginationBar :page="impactPageIndex" :size="impactPageSize" :total="impactTotal" :disabled="impactLoading || impactBusy" @change="changeImpactPage" @resize="resizeImpactPage" />
          </div>
        </template>
      </section>
    </div>

    <div v-if="impactCreateOpen && impactPolicy" class="modal-backdrop" @click.self="impactCreateOpen = false">
      <form class="panel modal-card" @submit.prevent="createImpact">
        <div class="modal-head">
          <div><span class="eyebrow">NEW POLICY IMPACT</span><h2>分析《{{ impactPolicy.title }}》对企业的影响</h2></div>
          <button type="button" class="icon-button" @click="impactCreateOpen = false">×</button>
        </div>
        <p>只列出当前协会正常存续的企业。生成前必须已有与该政策关联的已发布资料片段，系统不会在无证据时编造分析。</p>
        <div class="search-box compact"><span>⌕</span><input v-model="impactMemberQuery" placeholder="搜索企业名称" /></div>
        <div v-if="impactMemberError" class="save-message" role="alert">{{ impactMemberError }}</div>
        <div v-if="impactMemberLoading" class="empty-business-state"><b>正在加载企业…</b></div>
        <div v-else class="impact-member-list">
          <label v-for="member in impactMembers" :key="member.id" :class="{ selected: impactEnterpriseId === member.id }">
            <input v-model="impactEnterpriseId" type="radio" :value="member.id" />
            <span><strong>{{ member.name }}</strong><small>{{ member.role }} · {{ member.city || '地区未填写' }}</small></span>
          </label>
          <div v-if="!impactMembers.length && !impactMemberError" class="empty-business-state"><b>未找到可分析的正常企业</b></div>
          <PaginationBar :page="impactMemberPage" :size="impactMemberSize" :total="impactMemberTotal" :disabled="impactMemberLoading" @change="changeImpactMemberPage" @resize="resizeImpactMemberPage" />
        </div>
        <div class="form-actions">
          <button type="button" class="secondary-button" @click="impactCreateOpen = false">取消</button>
          <button class="primary-button" :disabled="impactBusy || !impactEnterpriseId">{{ impactBusy ? '正在生成…' : '生成并进入审核' }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.policy-filter { justify-content: flex-end; }
.policy-deleted-toggle { color: var(--muted); display: inline-flex; align-items: center; gap: 7px; font-size: 11px; }
.policy-deleted { opacity: .72; border-style: dashed; }
.policy-actions { flex-wrap: wrap; }
.danger-action { color: var(--danger); border-color: color-mix(in srgb, var(--danger), transparent 55%); }
.policy-history-list { max-height: 58vh; overflow: auto; display: grid; gap: 10px; }
.policy-history-list article { padding: 12px; border: 1px solid var(--line); border-radius: 8px; }
.policy-history-list article > div { display: flex; justify-content: space-between; gap: 12px; color: var(--muted); font-size: 10px; }
.policy-history-list strong { color: var(--ink); }
.policy-history-list pre { margin: 10px 0 0; padding: 10px; overflow: auto; border-radius: 6px; color: var(--muted); background: var(--primary-soft); font-size: 10px; white-space: pre-wrap; }
.impact-head-actions, .impact-row-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.impact-filter { justify-content: flex-end; margin-bottom: 12px; }
.impact-filter label { min-width: 160px; color: var(--muted); font-size: 11px; }
.impact-filter select { width: 100%; margin-top: 5px; }
.impact-history-section { margin-top: 18px; }
.impact-history-section h3 { margin: 0 0 10px; }
.impact-member-list { display: grid; gap: 8px; margin-top: 12px; max-height: 48vh; overflow: auto; }
.impact-member-list > label { display: flex; align-items: center; gap: 10px; padding: 11px 12px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.impact-member-list > label.selected { border-color: var(--primary); background: var(--primary-soft); }
.impact-member-list > label span { display: grid; gap: 3px; }
.impact-member-list small { color: var(--muted); }
</style>
