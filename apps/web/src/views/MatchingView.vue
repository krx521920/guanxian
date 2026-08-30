<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type {
  AssociationConsent,
  AssociationConsentTarget,
  Demand,
  MatchFeedback,
  MatchInvitation,
  MatchNegotiation,
  MatchOutcome,
  PersistedMatch,
} from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime } from './business-form'

const route = useRoute()
const auth = useAuth()
const items = ref<PersistedMatch[]>([])
const demands = ref<Demand[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const state = ref('全部')
const selected = ref<PersistedMatch | null>(null)
const rulesOpen = ref(false)
const generatorOpen = ref(false)
const selectedDemandId = ref('')
const closeReason = ref('')
const workflowLoading = ref(false)
const invitations = ref<MatchInvitation[]>([])
const negotiations = ref<MatchNegotiation[]>([])
const outcomes = ref<MatchOutcome[]>([])
const feedback = ref<MatchFeedback[]>([])
const lastFeedback = ref<MatchFeedback | null>(null)
const invitationMessage = ref('')
const invitationExpiresAt = ref('')
const invitationResponseComment = ref('')
const negotiationForm = reactive({ stage: 'INITIAL_CONTACT', summary: '', nextAction: '', nextActionAt: '' })
const feedbackForm = reactive({ rating: '', outcome: 'SUCCESS', closeReason: '', comment: '' })
const outcomeForm = reactive({ title: '', summary: '', contractAmount: '', resultType: 'COOPERATION', visibility: 'ASSOCIATION' })
const matchConsents = ref<AssociationConsent[]>([])
const matchConsentTargets = ref<AssociationConsentTarget[]>([])
const consentForm = reactive({ targetAssociationId: '', expiresAt: '' })
const states = computed(() => ['全部', ...new Set(items.value.map((item) => displayBusinessStatus(item.state)))])
const filtered = computed(() => items.value.filter((item) => state.value === '全部' || displayBusinessStatus(item.state) === state.value))
const recommendedCount = computed(() => items.value.filter((item) => Boolean(item.recommendedAt)).length)
const confirmedCount = computed(() => items.value.filter((item) => item.demandConfirmedAt && item.candidateConfirmedAt).length)
const canGenerate = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || ''))
const canRecommend = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const canConfirm = (item: PersistedMatch) => isEnterpriseAdmin.value
  && [item.demandEnterpriseId, item.candidateEnterpriseId].includes(auth.user.value?.enterpriseId || '')
  && ['PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED'].includes(item.state || '')
  && (auth.user.value?.enterpriseId === item.demandEnterpriseId ? !item.demandConfirmedAt : !item.candidateConfirmedAt)
const isAssociationStaff = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const isEnterpriseAdmin = computed(() => auth.user.value?.role === 'ENTERPRISE_ADMIN')
const canManageEnterpriseConsent = computed(() => isEnterpriseAdmin.value
  || (auth.user.value?.role === 'SYSTEM_ADMIN' && Boolean(auth.user.value.enterpriseId)))
const isDemandOwner = (item: PersistedMatch) => isEnterpriseAdmin.value && auth.user.value?.enterpriseId === item.demandEnterpriseId
const isCandidate = (item: PersistedMatch) => isEnterpriseAdmin.value && auth.user.value?.enterpriseId === item.candidateEnterpriseId
const canInvite = (item: PersistedMatch) => item.state === 'CONFIRMED'
  && (isAssociationStaff.value || isDemandOwner(item))
const canNegotiate = (item: PersistedMatch) => item.state === 'NEGOTIATING'
  && (isAssociationStaff.value || isDemandOwner(item) || isCandidate(item))
const canSubmitFeedback = (item: PersistedMatch) => ['OUTCOME_PENDING', 'CLOSED'].includes(item.state || '')
  && (isDemandOwner(item) || isCandidate(item))
const canArchiveOutcome = (item: PersistedMatch) => item.state === 'OUTCOME_PENDING'
  && (isAssociationStaff.value || isDemandOwner(item))
const canRespondInvitation = (item: MatchInvitation) => isEnterpriseAdmin.value
  && auth.user.value?.enterpriseId === item.recipientEnterpriseId
  && item.status === 'PENDING'
  && (!item.expiresAt || new Date(item.expiresAt).getTime() > Date.now())
const canCloseMatch = (item: PersistedMatch) => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || '')
  || (auth.user.value?.role === 'ENTERPRISE_ADMIN' && auth.user.value.enterpriseId === item.demandEnterpriseId)
const matchConsentHistory = computed(() => selected.value
  ? matchConsents.value.filter((consent) => consent.resourceType === 'MATCH' && consent.resourceId === selected.value?.id)
  : [])
const activeMatchConsents = computed(() => matchConsentHistory.value.filter(isConsentActive))
const eligibleMatchConsentTargets = computed(() => matchConsentTargets.value.filter((target) => target.resourceType === 'MATCH'))
const grantableMatchConsentTargets = computed(() => eligibleMatchConsentTargets.value.filter((target) =>
  !activeMatchConsents.value.some((consent) => consent.targetAssociationId === target.targetAssociationId)))
const selectedMatchConsentTarget = computed(() => eligibleMatchConsentTargets.value.find((target) =>
  target.targetAssociationId === consentForm.targetAssociationId))
const minimumConsentExpiry = localDateTime(new Date(Date.now() + 60_000))
const maximumConsentExpiry = computed(() => localDateTime(selectedMatchConsentTarget.value?.policyExpiresAt))
const canManageSelectedMatchConsent = computed(() => Boolean(selected.value
  && canManageEnterpriseConsent.value
  && [selected.value.demandEnterpriseId, selected.value.candidateEnterpriseId].includes(auth.user.value?.enterpriseId || '')
  && (eligibleMatchConsentTargets.value.length || matchConsentHistory.value.length)))

function toInstant(value: string): string | null {
  return value ? new Date(value).toISOString() : null
}

function localDateTime(value: string | Date | null | undefined): string {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function isConsentActive(item: AssociationConsent): boolean {
  return item.status === 'ACTIVE' && !item.revokedAt
    && (!item.expiresAt || new Date(item.expiresAt).getTime() > Date.now())
}

function defaultConsentExpiry(target: AssociationConsentTarget): string {
  const oneYear = Date.now() + 365 * 86_400_000
  const policyEnd = target.policyExpiresAt ? new Date(target.policyExpiresAt).getTime() : Number.POSITIVE_INFINITY
  return localDateTime(new Date(Math.min(oneYear, policyEnd)))
}

function prepareMatchConsent() {
  const first = grantableMatchConsentTargets.value[0]
  consentForm.targetAssociationId = first?.targetAssociationId || ''
  consentForm.expiresAt = first ? defaultConsentExpiry(first) : ''
}

function selectConsentTarget() {
  const target = selectedMatchConsentTarget.value
  consentForm.expiresAt = target ? defaultConsentExpiry(target) : ''
}

async function loadMatchConsentContext() {
  if (!canManageEnterpriseConsent.value) return
  try {
    [matchConsents.value, matchConsentTargets.value] = await Promise.all([
      platformApi.associationConsents(),
      platformApi.associationConsentTargets(),
    ])
  } catch (reason) {
    message.value = apiActionMessage(reason, '匹配记录已加载，但跨协会授权上下文暂时无法读取。')
  }
}

async function grantMatchConsent() {
  const item = selected.value
  const target = selectedMatchConsentTarget.value
  if (!item || !target || busy.value || !canManageSelectedMatchConsent.value) return
  const expiry = new Date(consentForm.expiresAt)
  if (Number.isNaN(expiry.getTime()) || expiry.getTime() <= Date.now()) {
    message.value = '跨协会授权必须设置未来的截止时间。'
    return
  }
  if (target.policyExpiresAt && expiry.getTime() > new Date(target.policyExpiresAt).getTime()) {
    message.value = '企业授权截止时间不能晚于协会字段策略截止时间。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.grantAssociationConsent({
      enterpriseId: null,
      targetAssociationId: target.targetAssociationId,
      resourceType: 'MATCH',
      resourceId: item.id,
      expiresAt: expiry.toISOString(),
    })
    matchConsents.value = [saved, ...matchConsents.value]
    prepareMatchConsent()
    message.value = '匹配记录的跨协会字段授权已生效。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '匹配记录授权失败，请确认关系和字段策略仍然有效。')
  } finally {
    busy.value = false
  }
}

async function revokeMatchConsent(item: AssociationConsent) {
  if (busy.value || !isConsentActive(item)) return
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.revokeAssociationConsent(item)
    matchConsents.value = matchConsents.value.map((value) => value.id === saved.id ? saved : value)
    prepareMatchConsent()
    message.value = '匹配记录的定向共享授权已撤销。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '撤销匹配记录授权失败。')
  } finally {
    busy.value = false
  }
}

async function openDetail(item: PersistedMatch) {
  selected.value = item
  prepareMatchConsent()
  closeReason.value = ''
  invitations.value = []
  negotiations.value = []
  outcomes.value = []
  feedback.value = []
  lastFeedback.value = null
  workflowLoading.value = true
  try {
    const [loadedInvitations, loadedNegotiations, loadedFeedback, loadedOutcomes] = await Promise.all([
      platformApi.matchInvitations(item.id),
      platformApi.matchNegotiations(item.id),
      platformApi.matchFeedback(item.id),
      platformApi.matchOutcomes(item.id),
    ])
    if (selected.value?.id !== item.id) return
    invitations.value = loadedInvitations
    negotiations.value = loadedNegotiations
    feedback.value = loadedFeedback
    lastFeedback.value = loadedFeedback.find((value) => value.enterpriseId === auth.user.value?.enterpriseId) || null
    outcomes.value = loadedOutcomes
    feedbackForm.outcome = item.state === 'OUTCOME_PENDING' ? 'SUCCESS' : 'NO_DEAL'
  } catch (reason) {
    message.value = apiActionMessage(reason, '匹配业务记录加载失败，请稍后重试。')
  } finally {
    workflowLoading.value = false
  }
}

async function refreshSelectedMatch(matchId: string) {
  const loaded = await platformApi.matches()
  items.value = loaded
  selected.value = loaded.find((item) => item.id === matchId) || null
  prepareMatchConsent()
}

async function load() {
  loading.value = true; error.value = null
  try {
    const [matches, demandPage] = await Promise.all([platformApi.matches(), platformApi.demands()])
    items.value = matches; demands.value = demandPage.items
    await loadMatchConsentContext()
    const fromRoute = typeof route.query.demand === 'string' ? route.query.demand : ''
    if (fromRoute && canGenerate.value) { selectedDemandId.value = fromRoute; generatorOpen.value = true }
  } catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
}

async function generate(closeDialog = true) {
  if (!selectedDemandId.value || busy.value) return
  busy.value = true; message.value = ''
  try {
    const generated = await platformApi.generateMatches(selectedDemandId.value)
    items.value = [...generated, ...items.value.filter((item) => item.demandId !== selectedDemandId.value)]
    if (closeDialog) generatorOpen.value = false
    message.value = generated.length ? `已为该需求生成 ${generated.length} 条可追踪匹配。` : '未找到符合条件的在架产品或服务，请先完善企业能力资料。'
  } catch (reason) { message.value = apiActionMessage(reason, '匹配生成失败，请确认需求已审核发布。') }
  finally { busy.value = false }
}

async function transition(action: 'recommend' | 'confirm') {
  if (!selected.value || busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.transitionMatch(selected.value, action)
    items.value = items.value.map((item) => item.id === saved.id ? saved : item); selected.value = saved
    message.value = action === 'recommend' ? '协会已将匹配定向推荐给企业。' : '企业已确认匹配，可进入洽谈与协作。'
  } catch (reason) { message.value = apiActionMessage(reason, '匹配状态更新失败。') }
  finally { busy.value = false }
}

async function closeMatch() {
  if (!selected.value || !closeReason.value.trim() || busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.closeMatch(selected.value, closeReason.value)
    items.value = items.value.map((item) => item.id === saved.id ? saved : item); selected.value = saved; closeReason.value = ''
    message.value = '匹配已关闭，原因已归档用于后续效果评估。'
  } catch (reason) { message.value = apiActionMessage(reason, '匹配关闭失败。') }
  finally { busy.value = false }
}

async function sendInvitation() {
  if (!selected.value || busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.inviteMatch(
      selected.value,
      isAssociationStaff.value ? 'ASSOCIATION_RECOMMENDATION' : 'ENTERPRISE',
      invitationMessage.value,
      toInstant(invitationExpiresAt.value),
    )
    invitations.value = [saved, ...invitations.value]
    await refreshSelectedMatch(saved.matchId)
    invitationMessage.value = ''; invitationExpiresAt.value = ''
    message.value = '定向邀请已发送并保存。'
  } catch (reason) { message.value = apiActionMessage(reason, '邀请发送失败。') }
  finally { busy.value = false }
}

async function respondInvitation(item: MatchInvitation, accepted: boolean) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.respondMatchInvitation(item, accepted, invitationResponseComment.value)
    invitations.value = invitations.value.map((invitation) => invitation.id === saved.id ? saved : invitation)
    await refreshSelectedMatch(saved.matchId)
    invitationResponseComment.value = ''
    message.value = accepted ? '已接受邀请，可继续记录洽谈进展。' : '已拒绝邀请，应答原因已保存。'
  } catch (reason) { message.value = apiActionMessage(reason, '邀请应答失败，请刷新后重试。') }
  finally { busy.value = false }
}

async function addNegotiation() {
  if (!selected.value || !negotiationForm.summary.trim() || busy.value) return
  busy.value = true; message.value = ''
  try {
    const matchId = selected.value.id
    const saved = await platformApi.addMatchNegotiation(selected.value, {
      stage: negotiationForm.stage,
      summary: negotiationForm.summary.trim(),
      nextAction: negotiationForm.nextAction.trim() || null,
      nextActionAt: toInstant(negotiationForm.nextActionAt),
    })
    negotiations.value = [saved, ...negotiations.value]
    await refreshSelectedMatch(matchId)
    negotiationForm.summary = ''; negotiationForm.nextAction = ''; negotiationForm.nextActionAt = ''
    message.value = '洽谈记录已保存。'
  } catch (reason) { message.value = apiActionMessage(reason, '洽谈记录保存失败。') }
  finally { busy.value = false }
}

async function submitFeedback() {
  if (!selected.value || !feedbackForm.outcome || busy.value) return
  busy.value = true; message.value = ''
  try {
    lastFeedback.value = await platformApi.submitMatchFeedback(selected.value.id, {
      rating: feedbackForm.rating ? Number(feedbackForm.rating) : null,
      outcome: feedbackForm.outcome,
      closeReason: feedbackForm.closeReason.trim() || null,
      comment: feedbackForm.comment.trim() || null,
    })
    feedback.value = [lastFeedback.value, ...feedback.value.filter((value) => value.id !== lastFeedback.value?.id)]
    feedbackForm.rating = ''; feedbackForm.closeReason = ''; feedbackForm.comment = ''
    message.value = '匹配反馈已提交，系统已保存企业评价。'
  } catch (reason) { message.value = apiActionMessage(reason, '匹配反馈提交失败。') }
  finally { busy.value = false }
}

async function archiveOutcome() {
  if (!selected.value || !outcomeForm.title.trim() || !outcomeForm.summary.trim() || busy.value) return
  busy.value = true; message.value = ''
  try {
    const matchId = selected.value.id
    const saved = await platformApi.archiveMatchOutcome(selected.value, {
      title: outcomeForm.title.trim(),
      summary: outcomeForm.summary.trim(),
      contractAmount: outcomeForm.contractAmount ? Number(outcomeForm.contractAmount) : null,
      resultType: outcomeForm.resultType,
      visibility: outcomeForm.visibility,
    })
    outcomes.value = [saved, ...outcomes.value]
    await refreshSelectedMatch(matchId)
    outcomeForm.title = ''; outcomeForm.summary = ''; outcomeForm.contractAmount = ''
    message.value = '合作成果已归档。'
  } catch (reason) { message.value = apiActionMessage(reason, '成果归档失败。') }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="ECOSYSTEM MATCHING" title="生态匹配" description="基于已审核的真实需求和在架能力，生成可确认、可反馈的匹配记录">
      <button class="secondary-button" @click="rulesOpen = true">匹配依据</button><button v-if="canGenerate" class="primary-button" @click="generatorOpen = true">生成新一轮匹配</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="match-summary">
      <div><span>当前匹配记录</span><strong>{{ items.length }}</strong><small>来自数据库</small></div><div><span>已由协会推荐</span><strong>{{ recommendedCount }}</strong><small>{{ items.length ? `${Math.round(recommendedCount / items.length * 100)}%` : '0%' }}</small></div><div><span>已由参与企业确认</span><strong>{{ confirmedCount }}</strong><small>{{ items.length ? `${Math.round(confirmedCount / items.length * 100)}%` : '0%' }}</small></div>
      <div class="matching-logic"><span class="ai-chip">规则</span><p><b>记录可解释</b>每条匹配保留分数和具体推荐理由，并由协会与企业人工确认。</p></div>
    </section>
    <div class="segmented match-tabs"><button v-for="itemState in states" :key="itemState" :class="{ active: state === itemState }" @click="state = itemState">{{ itemState }}</button></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="match-list">
      <article v-for="item in filtered" :key="item.id" class="match-card panel">
        <div class="match-score"><svg viewBox="0 0 44 44"><circle cx="22" cy="22" r="18"/><circle class="score-line" cx="22" cy="22" r="18" :style="{ strokeDashoffset: `${113 - (item.score ?? 0) * 1.13}` }"/></svg><div><strong>{{ item.score ?? '—' }}</strong><span>匹配度</span></div></div>
        <div class="match-demand"><span class="eyebrow">需求方 · {{ item.scene || '场景未授权' }}</span><h2>{{ item.demandTitle || '需求标题未授权' }}</h2><p>{{ item.demandCompany || '需求企业未授权' }}</p></div>
        <div class="match-arrow"><span>可解释推荐</span>→</div>
        <div class="match-supplier"><span class="eyebrow">能力供给方</span><h2>{{ item.supplierCompany || '供给企业未授权' }}</h2><p>{{ item.solution || '方案未授权' }}</p></div>
        <div class="match-actions"><StatusBadge :value="displayBusinessStatus(item.state)" /><small>{{ formatDateTime(item.updatedAt) }}</small><button class="primary-button small" @click="openDetail(item)">查看匹配详情</button></div>
        <div class="match-reasons"><b>推荐理由</b><span v-for="reason in item.reasons" :key="reason">✓ {{ reason }}</span><span v-if="!item.reasons.length">暂无理由说明</span></div>
      </article>
      <div v-if="!filtered.length" class="panel empty-business-state"><b>暂无匹配记录</b><span>企业管理员可选择已发布需求生成匹配；数据不足时系统会如实显示空状态。</span></div>
    </section>

    <div v-if="rulesOpen" class="modal-backdrop" @click.self="rulesOpen = false"><section class="panel modal-card compact-modal"><div class="modal-head"><div><span class="eyebrow">MATCH EXPLAINABILITY</span><h2>匹配依据</h2></div><button class="icon-button" @click="rulesOpen = false">×</button></div><div class="modal-copy"><p>系统从需求场景、所需能力、供给方产品/服务、资质与数据可见性中生成候选。</p><p>分数和推荐理由以后端每条记录为准，页面不伪造固定权重。</p><p>匹配不会自动对外推送：必须经协会推荐、企业确认后才进入洽谈。</p></div><div class="form-actions"><button class="primary-button" @click="rulesOpen = false">我知道了</button></div></section></div>
    <div v-if="generatorOpen" class="modal-backdrop" @click.self="generatorOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="generate()"><div class="modal-head"><div><span class="eyebrow">GENERATE MATCHES</span><h2>选择真实需求</h2></div><button type="button" class="icon-button" @click="generatorOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>需求 *</span><select v-model="selectedDemandId" required><option value="" disabled>请选择</option><option v-for="demand in demands" :key="demand.id" :value="demand.id">{{ demand.title }} · {{ demand.enterpriseName }}</option></select></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="generatorOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '正在匹配…' : '生成并保存匹配' }}</button></div></form></div>
    <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
      <section class="panel modal-card match-detail-modal">
        <div class="modal-head">
          <div><span class="eyebrow">MATCH DETAIL</span><h2>{{ selected.demandTitle || '需求标题未授权' }}</h2></div>
          <button class="icon-button" @click="selected = null">×</button>
        </div>
        <div v-if="message" class="save-message modal-message" aria-live="polite">{{ message }}</div>
        <div class="detail-grid">
          <div><span>需求方</span><strong>{{ selected.demandCompany || '未授权' }}</strong></div>
          <div><span>供给方</span><strong>{{ selected.supplierCompany || '未授权' }}</strong></div>
          <div><span>匹配度</span><strong>{{ selected.score ?? '未授权' }}</strong></div>
          <div><span>当前状态</span><strong>{{ displayBusinessStatus(selected.state) }}</strong></div>
          <div><span>需求方确认</span><strong>{{ selected.demandConfirmedAt ? formatDateTime(selected.demandConfirmedAt) : '待确认' }}</strong></div>
          <div><span>供给方确认</span><strong>{{ selected.candidateConfirmedAt ? formatDateTime(selected.candidateConfirmedAt) : '待确认' }}</strong></div>
        </div>
        <div class="modal-copy">
          <h3>推荐方案</h3><p>{{ selected.solution || '该字段未获跨协会授权。' }}</p>
          <h3>推荐理由</h3><ul><li v-for="reason in selected.reasons" :key="reason">{{ reason }}</li></ul>
          <p v-if="selected.closedReason"><b>关闭原因：</b>{{ selected.closedReason }}</p>
        </div>
        <div v-if="canCloseMatch(selected) && !['CLOSED', 'ARCHIVED'].includes(selected.state || '')" class="close-inline">
          <input v-model="closeReason" placeholder="关闭时必须填写原因" />
          <button class="text-button danger-text" :disabled="!closeReason.trim() || busy" @click="closeMatch">关闭匹配</button>
        </div>
        <div class="form-actions match-state-actions">
          <button v-if="canRecommend && !selected.recommendedAt && ['PENDING_CONFIRMATION', 'PARTIALLY_CONFIRMED'].includes(selected.state || '')" class="primary-button" :disabled="busy" @click="transition('recommend')">协会推荐</button>
          <button v-if="canConfirm(selected)" class="primary-button" :disabled="busy" @click="transition('confirm')">确认本方意向</button>
          <RouterLink v-if="['NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED'].includes(selected.state || '')" class="primary-button" :to="`/collaborations?match=${selected.id}`">进入协作事项</RouterLink>
        </div>

        <div class="match-workflow">
          <section v-if="canManageSelectedMatchConsent" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">PARTNER FIELD CONSENT</span><h3>匹配记录跨协会授权</h3></div><small>仅开放协会策略勾选的字段</small></div>
            <p class="workflow-note">需求方或供给方企业管理员只能授权本企业参与的这条真实匹配；关系、字段策略或企业授权任一失效都会停止共享。</p>
            <form v-if="grantableMatchConsentTargets.length" class="workflow-form" @submit.prevent="grantMatchConsent">
              <label><span>目标协会 *</span><select v-model="consentForm.targetAssociationId" required @change="selectConsentTarget"><option value="" disabled>请选择</option><option v-for="target in grantableMatchConsentTargets" :key="target.targetAssociationId" :value="target.targetAssociationId">{{ target.targetAssociationId }}</option></select></label>
              <label><span>授权截止时间 *</span><input v-model="consentForm.expiresAt" type="datetime-local" :min="minimumConsentExpiry" :max="maximumConsentExpiry || undefined" required /></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">确认跨协会授权</button></div>
            </form>
            <div v-if="matchConsentHistory.length" class="workflow-record-list">
              <article v-for="consent in matchConsentHistory" :key="consent.id" class="workflow-record">
                <div class="workflow-record-head"><StatusBadge :value="displayBusinessStatus(isConsentActive(consent) ? 'ACTIVE' : consent.revokedAt ? 'REVOKED' : 'EXPIRED')" /><small>{{ formatDateTime(consent.createdAt) }}</small></div>
                <p>目标协会：{{ consent.targetAssociationId }}</p><small>授权截止：{{ formatDateTime(consent.expiresAt) }}</small>
                <button v-if="isConsentActive(consent)" type="button" class="text-button danger-text" :disabled="busy" @click="revokeMatchConsent(consent)">撤销授权</button>
              </article>
            </div>
            <div v-else-if="!grantableMatchConsentTargets.length" class="workflow-empty">暂无可用 MATCH 字段策略或有效授权记录。</div>
          </section>

          <div v-if="workflowLoading" class="workflow-loading">正在加载邀请、洽谈和成果记录…</div>

          <section v-else class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">INVITATIONS</span><h3>定向邀请与应答</h3></div><small>{{ invitations.length }} 条记录</small></div>
            <div v-if="invitations.length" class="workflow-record-list">
              <article v-for="invitation in invitations" :key="invitation.id" class="workflow-record">
                <div class="workflow-record-head"><StatusBadge :value="displayBusinessStatus(invitation.status)" /><small>{{ formatDateTime(invitation.createdAt) }}</small></div>
                <p>{{ invitation.message || '邀请方未附加说明。' }}</p>
                <small>类型：{{ invitation.invitationType === 'ASSOCIATION_RECOMMENDATION' ? '协会推荐' : '企业邀请' }}<template v-if="invitation.expiresAt"> · 截止 {{ formatDateTime(invitation.expiresAt) }}</template></small>
                <p v-if="invitation.responseComment" class="workflow-response">应答说明：{{ invitation.responseComment }}</p>
                <form v-if="canRespondInvitation(invitation)" class="workflow-inline-form" @submit.prevent="respondInvitation(invitation, true)">
                  <input v-model="invitationResponseComment" maxlength="2000" placeholder="应答说明（可选）" />
                  <button type="button" class="secondary-button small" :disabled="busy" @click="respondInvitation(invitation, false)">拒绝</button>
                  <button class="primary-button small" :disabled="busy">接受</button>
                </form>
              </article>
            </div>
            <div v-else class="workflow-empty">尚未发送定向邀请。</div>
            <form v-if="canInvite(selected)" class="workflow-form" @submit.prevent="sendInvitation">
              <label class="form-span-2"><span>邀请说明</span><textarea v-model="invitationMessage" maxlength="2000" rows="2" placeholder="说明合作方向、预期或联系人"></textarea></label>
              <label><span>应答截止时间</span><input v-model="invitationExpiresAt" type="datetime-local" /></label>
              <div class="workflow-submit"><button class="primary-button small" :disabled="busy">发送定向邀请</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">NEGOTIATIONS</span><h3>洽谈进度</h3></div><small>{{ negotiations.length }} 条记录</small></div>
            <div v-if="negotiations.length" class="workflow-record-list">
              <article v-for="record in negotiations" :key="record.id" class="workflow-record">
                <div class="workflow-record-head"><b>{{ displayBusinessStatus(record.stage) }}</b><small>{{ formatDateTime(record.createdAt) }}</small></div>
                <p>{{ record.summary }}</p>
                <small v-if="record.nextAction">下一步：{{ record.nextAction }}<template v-if="record.nextActionAt"> · {{ formatDateTime(record.nextActionAt) }}</template></small>
              </article>
            </div>
            <div v-else class="workflow-empty">尚无洽谈记录。</div>
            <form v-if="canNegotiate(selected)" class="workflow-form" @submit.prevent="addNegotiation">
              <label><span>洽谈阶段 *</span><select v-model="negotiationForm.stage" required><option value="INITIAL_CONTACT">初次联系</option><option value="TECHNICAL_EXCHANGE">技术交流</option><option value="COMMERCIAL_NEGOTIATION">商务洽谈</option><option value="CONTRACTING">合同推进</option><option value="CONTRACT_SIGNED">合同已签署</option><option value="TERMINATED">终止洽谈</option></select></label>
              <label><span>下次行动时间</span><input v-model="negotiationForm.nextActionAt" type="datetime-local" /></label>
              <label class="form-span-2"><span>进展摘要 *</span><textarea v-model="negotiationForm.summary" maxlength="5000" rows="2" required></textarea></label>
              <label class="form-span-2"><span>下一步行动</span><input v-model="negotiationForm.nextAction" maxlength="1000" placeholder="如：安排现场勘查" /></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">保存洽谈记录</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading && canSubmitFeedback(selected)" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">FEEDBACK</span><h3>企业匹配反馈</h3></div><small>{{ feedback.length }}/2 方已反馈</small></div>
            <div v-if="lastFeedback" class="workflow-record submitted-feedback"><b>本次反馈已保存</b><span>{{ displayBusinessStatus(lastFeedback.outcome) }}<template v-if="lastFeedback.rating"> · {{ lastFeedback.rating }} 分</template></span><small>{{ formatDateTime(lastFeedback.submittedAt) }}</small></div>
            <form class="workflow-form" @submit.prevent="submitFeedback">
              <label><span>结果 *</span><select v-model="feedbackForm.outcome" required><option v-if="selected.state === 'OUTCOME_PENDING'" value="SUCCESS">已达成合作</option><template v-else><option value="NO_DEAL">未达成合作</option><option value="WITHDRAWN">主动退出</option></template></select></label>
              <label><span>评分</span><select v-model="feedbackForm.rating"><option value="">暂不评分</option><option v-for="score in 5" :key="score" :value="String(score)">{{ score }} 分</option></select></label>
              <label class="form-span-2"><span>反馈说明</span><textarea v-model="feedbackForm.comment" maxlength="3000" rows="2"></textarea></label>
              <label v-if="feedbackForm.outcome !== 'SUCCESS'" class="form-span-2"><span>未达成原因 *</span><input v-model="feedbackForm.closeReason" maxlength="1000" required /></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">提交企业反馈</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">OUTCOMES</span><h3>合作成果归档</h3></div><small>{{ outcomes.length }} 项成果</small></div>
            <div v-if="outcomes.length" class="workflow-record-list">
              <article v-for="outcome in outcomes" :key="outcome.id" class="workflow-record">
                <div class="workflow-record-head"><b>{{ outcome.title }}</b><small>{{ formatDateTime(outcome.archivedAt) }}</small></div>
                <p>{{ outcome.summary }}</p>
                <small>{{ displayBusinessStatus(outcome.resultType) }} · {{ displayBusinessStatus(outcome.visibility) }}<template v-if="outcome.contractAmount !== null"> · 合同金额 {{ outcome.contractAmount.toLocaleString('zh-CN') }} 元</template></small>
              </article>
            </div>
            <div v-else class="workflow-empty">尚无已归档成果。</div>
            <form v-if="canArchiveOutcome(selected)" class="workflow-form" @submit.prevent="archiveOutcome">
              <label><span>成果标题 *</span><input v-model="outcomeForm.title" maxlength="300" required /></label>
              <label><span>合同金额（元）</span><input v-model="outcomeForm.contractAmount" type="number" min="0" step="0.01" /></label>
              <label class="form-span-2"><span>成果摘要 *</span><textarea v-model="outcomeForm.summary" maxlength="5000" rows="3" required></textarea></label>
              <label><span>成果类型 *</span><select v-model="outcomeForm.resultType" required><option value="COOPERATION">合作落地</option><option value="CONTRACT">合同签订</option><option value="PILOT">试点项目</option><option value="TECHNICAL_RESULT">技术成果</option></select></label>
              <label><span>可见范围 *</span><select v-model="outcomeForm.visibility" required><option value="PRIVATE">仅归档人</option><option value="ENTERPRISES">参与企业</option><option value="ASSOCIATION">协会</option><option value="PARTNERS">合作协会</option><option value="PUBLIC">公开</option></select></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">归档合作成果</button></div>
            </form>
            <p v-else-if="(isAssociationStaff || isDemandOwner(selected))" class="workflow-note">仅合同签署后进入成果待归档阶段，且双方均提交成功反馈，才可归档成果。</p>
          </section>
        </div>
      </section>
    </div>
  </div>
</template>
