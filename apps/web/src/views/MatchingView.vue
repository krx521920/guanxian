<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type {
  AssociationConsent,
  AssociationConsentTarget,
  Demand,
  MatchFeedback,
  MatchInvitation,
  MatchNegotiation,
  MatchNegotiationStage,
  MatchOutcome,
  PersistedMatch,
} from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime } from './business-form'
import {
  applyMatchPage,
  MATCH_STATE_FILTERS,
  reconcileSavedMatch,
  type MatchStateFilter,
} from './matching-view'
import {
  availableNegotiationStages,
  canOpenMatchCollaboration,
  canRespondToInvitation,
  feedbackOutcomeForState,
  hasMatchAction,
  isFutureLocalDateTime,
  isInvitationResponder,
  isValidInvitationResponse,
  loadMatchWorkflowSections,
} from './match-workflow'

const route = useRoute()
const auth = useAuth()
const items = ref<PersistedMatch[]>([])
const total = ref(0)
const demands = ref<Demand[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const state = ref<MatchStateFilter>('')
const page = ref(0)
const size = ref(20)
const selected = ref<PersistedMatch | null>(null)
const rulesOpen = ref(false)
const generatorOpen = ref(false)
const selectedDemandId = ref('')
const closeReason = ref('')
const workflowLoading = ref(false)
const workflowError = ref('')
const workflowSectionErrors = reactive({ invitations: '', negotiations: '', feedback: '', outcomes: '' })
const workflowSectionReadable = reactive({ invitations: false, negotiations: false, feedback: false, outcomes: false })
const currentTime = ref(Date.now())
const invitations = ref<MatchInvitation[]>([])
const negotiations = ref<MatchNegotiation[]>([])
const outcomes = ref<MatchOutcome[]>([])
const feedback = ref<MatchFeedback[]>([])
const lastFeedback = ref<MatchFeedback | null>(null)
const invitationMessage = ref('')
const invitationExpiresAt = ref('')
const invitationResponseComment = ref('')
const negotiationForm = reactive<{ stage: MatchNegotiationStage; summary: string; nextAction: string; nextActionAt: string }>({
  stage: 'INITIAL_CONTACT', summary: '', nextAction: '', nextActionAt: '',
})
const feedbackForm = reactive({ rating: '', outcome: 'SUCCESS', closeReason: '', comment: '' })
const outcomeForm = reactive({ title: '', summary: '', contractAmount: '', resultType: 'COOPERATION', visibility: 'ASSOCIATION' })
const matchConsents = ref<AssociationConsent[]>([])
const matchConsentTargets = ref<AssociationConsentTarget[]>([])
const consentForm = reactive({ targetAssociationId: '', expiresAt: '' })
const recommendedCount = computed(() => items.value.filter((item) => Boolean(item.recommendedAt)).length)
const confirmedCount = computed(() => items.value.filter((item) => item.demandConfirmedAt && item.candidateConfirmedAt).length)
const hasGenerationRole = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || ''))
const hasGenerationContext = computed(() => hasGenerationRole.value
  && (auth.user.value?.role !== 'SYSTEM_ADMIN' || Boolean(auth.user.value.associationId)))
const canGenerate = computed(() => hasGenerationContext.value && demands.value.length > 0)
const isEnterpriseAdmin = computed(() => auth.user.value?.role === 'ENTERPRISE_ADMIN')
const canManageEnterpriseConsent = computed(() => isEnterpriseAdmin.value
  || (auth.user.value?.role === 'SYSTEM_ADMIN' && Boolean(auth.user.value.enterpriseId)))
const canRecommend = (item: PersistedMatch) => hasMatchAction(item, 'RECOMMEND')
const canConfirm = (item: PersistedMatch) => hasMatchAction(item, 'CONFIRM')
const canInvite = (item: PersistedMatch) => hasMatchAction(item, 'INVITE')
const canNegotiate = (item: PersistedMatch) => hasMatchAction(item, 'NEGOTIATE')
const canSubmitFeedback = (item: PersistedMatch) => hasMatchAction(item, 'FEEDBACK')
const canArchiveOutcome = (item: PersistedMatch) => hasMatchAction(item, 'ARCHIVE')
const canCloseMatch = (item: PersistedMatch) => hasMatchAction(item, 'CLOSE')
const canOpenCollaboration = (item: PersistedMatch) => canOpenMatchCollaboration(item, auth.user.value, workflowSectionReadable)
const canRespondInvitation = (item: MatchInvitation) => canRespondToInvitation(item, auth.user.value, currentTime.value)
const canViewInvitationResponse = (item: MatchInvitation) => isInvitationResponder(item, auth.user.value)
  && ['PENDING', 'EXPIRED'].includes(invitationStatus(item))
const invitationStatus = (item: MatchInvitation) => {
  if (item.status !== 'PENDING' || !item.expiresAt) return item.status
  const expiry = new Date(item.expiresAt).getTime()
  return !Number.isFinite(expiry) || expiry <= currentTime.value ? 'EXPIRED' : item.status
}
const sendsAssociationInvitation = computed(() => ['ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || '')
  || (auth.user.value?.role === 'SYSTEM_ADMIN' && !auth.user.value.enterpriseId))
const negotiationStageOptions = computed(() => availableNegotiationStages(negotiations.value))
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
let workflowRequestSequence = 0
let loadRequestSequence = 0
let invitationClockTimer: number | null = null
let viewActive = true

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

async function loadMatchConsentContext(sequence = loadRequestSequence) {
  if (!canManageEnterpriseConsent.value) return
  try {
    const context = await Promise.all([
      platformApi.associationConsents(),
      platformApi.associationConsentTargets(),
    ])
    if (!viewActive || sequence !== loadRequestSequence) return
    matchConsents.value = context[0]
    matchConsentTargets.value = context[1]
  } catch (reason) {
    if (!viewActive || sequence !== loadRequestSequence) return
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

async function loadWorkflow(item: PersistedMatch) {
  const sequence = ++workflowRequestSequence
  selected.value = item
  prepareMatchConsent()
  closeReason.value = ''
  invitations.value = []
  negotiations.value = []
  outcomes.value = []
  feedback.value = []
  lastFeedback.value = null
  workflowLoading.value = true
  workflowError.value = ''
  Object.assign(workflowSectionErrors, { invitations: '', negotiations: '', feedback: '', outcomes: '' })
  Object.assign(workflowSectionReadable, { invitations: false, negotiations: false, feedback: false, outcomes: false })
  try {
    const loaded = await loadMatchWorkflowSections(platformApi, item.id)
    if (sequence !== workflowRequestSequence || selected.value?.id !== item.id) return
    invitations.value = loaded.invitations.status === 'fulfilled' ? loaded.invitations.value : []
    negotiations.value = loaded.negotiations.status === 'fulfilled' ? loaded.negotiations.value : []
    feedback.value = loaded.feedback.status === 'fulfilled' ? loaded.feedback.value : []
    outcomes.value = loaded.outcomes.status === 'fulfilled' ? loaded.outcomes.value : []
    workflowSectionReadable.invitations = loaded.invitations.status === 'fulfilled'
    workflowSectionReadable.negotiations = loaded.negotiations.status === 'fulfilled'
    workflowSectionReadable.feedback = loaded.feedback.status === 'fulfilled'
    workflowSectionReadable.outcomes = loaded.outcomes.status === 'fulfilled'
    workflowSectionErrors.invitations = loaded.invitations.status === 'rejected'
      ? apiActionMessage(loaded.invitations.reason, '邀请记录暂时无法读取。') : ''
    workflowSectionErrors.negotiations = loaded.negotiations.status === 'rejected'
      ? apiActionMessage(loaded.negotiations.reason, '洽谈记录暂时无法读取。') : ''
    workflowSectionErrors.feedback = loaded.feedback.status === 'rejected'
      ? apiActionMessage(loaded.feedback.reason, '企业反馈暂时无法读取。') : ''
    workflowSectionErrors.outcomes = loaded.outcomes.status === 'rejected'
      ? apiActionMessage(loaded.outcomes.reason, '成果归档暂时无法读取。') : ''
    const failures = Object.values(workflowSectionErrors).filter(Boolean).length
    if (failures === 4) workflowError.value = '邀请、洽谈、反馈和成果记录均未能加载，请重新加载。'
    try {
      await refreshSelectedMatch(item.id)
    } catch {
      if (selected.value?.id === item.id) selected.value = { ...item, allowedActions: [] }
      workflowError.value = '最新匹配状态刷新失败；已关闭写入按钮，以下成功读取的记录仍可查看。'
    }
    if (sequence !== workflowRequestSequence || selected.value?.id !== item.id) return
    lastFeedback.value = feedback.value.find((value) => value.enterpriseId === auth.user.value?.enterpriseId) || null
    prepareFeedbackForm(lastFeedback.value, selected.value.state)
    negotiationForm.stage = availableNegotiationStages(negotiations.value)[0]?.value || 'INITIAL_CONTACT'
  } catch (reason) {
    if (sequence !== workflowRequestSequence) return
    workflowError.value = apiActionMessage(reason, '匹配业务记录加载失败，请稍后重试。')
  } finally {
    if (sequence === workflowRequestSequence) workflowLoading.value = false
  }
}

function prepareFeedbackForm(existing: MatchFeedback | null, matchState: string | null | undefined) {
  feedbackForm.rating = existing?.rating ? String(existing.rating) : ''
  feedbackForm.outcome = feedbackOutcomeForState(existing?.outcome, matchState)
  feedbackForm.closeReason = existing?.outcome === feedbackForm.outcome ? existing?.closeReason || '' : ''
  feedbackForm.comment = existing?.comment || ''
}

async function openDetail(item: PersistedMatch) {
  message.value = ''
  await loadWorkflow(item)
}

function closeDetail() {
  workflowRequestSequence += 1
  selected.value = null
  workflowLoading.value = false
  workflowError.value = ''
}

async function refreshSelectedMatch(matchId: string) {
  const [current, pageResult] = await Promise.all([
    platformApi.match(matchId),
    readMatchPage(),
  ])
  const loaded = pageResult
  items.value = loaded.items
  total.value = loaded.total
  page.value = loaded.page
  size.value = loaded.size
  selected.value = current
  prepareMatchConsent()
}

async function acceptSavedMatch(saved: PersistedMatch, success: string) {
  const reconciled = reconcileSavedMatch(items.value, total.value, saved, state.value)
  items.value = reconciled.items
  total.value = reconciled.total
  selected.value = saved
  try {
    const currentPage = await readMatchPage()
    items.value = currentPage.items
    total.value = currentPage.total
    page.value = currentPage.page
    size.value = currentPage.size
    selected.value = saved
    message.value = success
  } catch {
    selected.value = { ...saved, allowedActions: [] }
    message.value = '操作已保存，但列表刷新失败；为避免基于旧版本继续操作，写入按钮已暂时关闭，请重新加载。'
  }
}

async function readMatchPage() {
  const requestedPage = page.value
  const first = applyMatchPage(await platformApi.matches(requestedPage, size.value, state.value))
  if (!first.items.length && first.total > 0 && first.page !== requestedPage) {
    return applyMatchPage(await platformApi.matches(first.page, first.size, state.value))
  }
  return first
}

async function refreshAfterSaved(matchId: string): Promise<boolean> {
  try {
    await refreshSelectedMatch(matchId)
    return true
  } catch {
    if (selected.value?.id === matchId) selected.value = { ...selected.value, allowedActions: [] }
    message.value = '操作已保存，但最新匹配状态刷新失败；为避免重复操作，写入按钮已暂时关闭，请重新加载。'
    return false
  }
}

async function handleMatchActionFailure(reason: unknown, fallback: string, matchId: string) {
  const actionMessage = apiActionMessage(reason, fallback)
  if (reason instanceof ApiRequestError
    && (reason.status === 409 || reason.status === 412 || reason.code === 'REQUEST_TIMEOUT')) {
    try {
      const current = selected.value
      if (current?.id === matchId) await loadWorkflow(current)
      else await load()
    } catch {
      // Keep the original conflict as the primary user-facing error.
    }
  }
  message.value = actionMessage
}

async function load() {
  const sequence = ++loadRequestSequence
  loading.value = true; error.value = null
  try {
    const [matchResult, generationResult] = await Promise.all([
      readMatchPage(),
      loadGenerationDemands()
        .then((value): { value: Demand[]; reason: unknown | null } => ({ value, reason: null }))
        .catch((reason: unknown) => ({ value: [] as Demand[], reason })),
    ])
    if (!viewActive || sequence !== loadRequestSequence) return
    const matches = matchResult
    items.value = matches.items
    total.value = matches.total
    page.value = matches.page
    size.value = matches.size
    demands.value = generationResult.value
    if (generationResult.reason) {
      message.value = apiActionMessage(
        generationResult.reason,
        '匹配记录已加载，但可生成需求暂时无法读取；生成功能已关闭。',
      )
    }
    await loadMatchConsentContext(sequence)
    if (!viewActive || sequence !== loadRequestSequence) return
    const fromRoute = typeof route.query.demand === 'string' ? route.query.demand : ''
    if (fromRoute && hasGenerationRole.value) {
      const target = demands.value.find((item) => item.id === fromRoute)
      selectedDemandId.value = target?.id || ''
      generatorOpen.value = Boolean(target)
      if (!target) message.value = '该需求当前不可用于生成匹配，可能尚未开放或不在当前身份的数据范围内。'
    } else if (selectedDemandId.value && !demands.value.some((item) => item.id === selectedDemandId.value)) {
      selectedDemandId.value = ''
    }
    const matchFromRoute = typeof route.query.match === 'string' ? route.query.match : ''
    if (matchFromRoute && selected.value?.id !== matchFromRoute) {
      try {
        const target = await platformApi.match(matchFromRoute)
        if (!viewActive || sequence !== loadRequestSequence) return
        await loadWorkflow(target)
      } catch (reason) {
        if (viewActive && sequence === loadRequestSequence) {
          message.value = apiActionMessage(reason, '关联匹配不存在或当前身份无权查看。')
        }
      }
    }
  } catch (reason) {
    if (viewActive && sequence === loadRequestSequence) error.value = safePageResourceError(reason)
  } finally {
    if (viewActive && sequence === loadRequestSequence) loading.value = false
  }
}

async function loadGenerationDemands(): Promise<Demand[]> {
  if (!hasGenerationContext.value) return []
  const loaded: Demand[] = []
  let demandPage = 0
  let total = 0
  do {
    const result = await platformApi.matchGenerationDemands(demandPage, 100)
    loaded.push(...result.items)
    total = result.total
    demandPage += 1
    if (!result.items.length) break
  } while (loaded.length < total)
  return loaded
}

function selectState(value: MatchStateFilter) {
  state.value = value
  page.value = 0
  void load()
}

function openGenerator() {
  message.value = ''
  generatorOpen.value = true
}

function changePage(value: number) {
  page.value = value
  void load()
}

function resizePage(value: number) {
  size.value = value
  page.value = 0
  void load()
}

async function generate(closeDialog = true) {
  if (!selectedDemandId.value || busy.value) return
  busy.value = true; message.value = ''
  try {
    const generated = await platformApi.generateMatches(selectedDemandId.value)
    page.value = 0
    state.value = ''
    await load()
    if (closeDialog) generatorOpen.value = false
    message.value = generated.length
      ? `本轮已新增或刷新 ${generated.length} 条可追踪匹配。`
      : '本轮未新增可刷新候选；已进入推荐或推进阶段的匹配会原样保留。'
  } catch (reason) {
    const actionMessage = apiActionMessage(reason, '匹配生成失败，请确认需求已审核发布。')
    if (reason instanceof ApiRequestError && reason.code === 'REQUEST_TIMEOUT') {
      try { await load() } catch { /* Preserve the original timeout message. */ }
    }
    message.value = actionMessage
  }
  finally { busy.value = false }
}

async function transition(action: 'recommend' | 'confirm') {
  if (!selected.value || busy.value || !hasMatchAction(selected.value, action === 'recommend' ? 'RECOMMEND' : 'CONFIRM')) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.transitionMatch(selected.value, action)
    await acceptSavedMatch(saved, action === 'recommend'
      ? '协会已将匹配定向推荐给企业。'
      : '企业已确认匹配，可进入洽谈与协作。')
  } catch (reason) { await handleMatchActionFailure(reason, '匹配状态更新失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

async function closeMatch() {
  if (!selected.value || !closeReason.value.trim() || busy.value || !canCloseMatch(selected.value)) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.closeMatch(selected.value, closeReason.value)
    closeReason.value = ''
    await acceptSavedMatch(saved, '匹配已关闭，原因已归档用于后续效果评估。')
  } catch (reason) { await handleMatchActionFailure(reason, '匹配关闭失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

async function sendInvitation() {
  if (!selected.value || busy.value || !canInvite(selected.value)) return
  if (!isFutureLocalDateTime(invitationExpiresAt.value)) {
    message.value = '应答截止时间必须晚于当前时间。'
    return
  }
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.inviteMatch(
      selected.value,
      sendsAssociationInvitation.value ? 'ASSOCIATION_RECOMMENDATION' : 'ENTERPRISE',
      invitationMessage.value,
      toInstant(invitationExpiresAt.value),
    )
    invitations.value = [saved, ...invitations.value]
    if (!await refreshAfterSaved(saved.matchId)) return
    invitationMessage.value = ''; invitationExpiresAt.value = ''
    message.value = '定向邀请已发送并保存。'
  } catch (reason) { await handleMatchActionFailure(reason, '邀请发送失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

async function respondInvitation(item: MatchInvitation, accepted: boolean) {
  if (busy.value) return
  currentTime.value = Date.now()
  if (!canRespondInvitation(item)) {
    message.value = invitationStatus(item) === 'EXPIRED' ? '邀请已过期，不能再应答。' : '当前身份不能应答这条邀请。'
    return
  }
  if (!isValidInvitationResponse(accepted, invitationResponseComment.value)) {
    message.value = '拒绝邀请时必须填写原因；接受邀请时说明可以留空。'
    return
  }
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.respondMatchInvitation(item, accepted, invitationResponseComment.value)
    invitations.value = invitations.value.map((invitation) => invitation.id === saved.id ? saved : invitation)
    if (!await refreshAfterSaved(saved.matchId)) return
    invitationResponseComment.value = ''
    message.value = accepted ? '已接受邀请，可继续记录洽谈进展。' : '已拒绝邀请，应答原因已保存。'
  } catch (reason) { await handleMatchActionFailure(reason, '邀请应答失败，请刷新后重试。', item.matchId) }
  finally { busy.value = false }
}

async function addNegotiation() {
  if (!selected.value || !negotiationForm.summary.trim() || busy.value || !canNegotiate(selected.value)
    || !negotiationStageOptions.value.some((option) => option.value === negotiationForm.stage)) return
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
    if (!await refreshAfterSaved(matchId)) return
    negotiationForm.summary = ''; negotiationForm.nextAction = ''; negotiationForm.nextActionAt = ''
    message.value = '洽谈记录已保存。'
  } catch (reason) { await handleMatchActionFailure(reason, '洽谈记录保存失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

async function submitFeedback() {
  if (!selected.value || !feedbackForm.outcome || busy.value || !canSubmitFeedback(selected.value)) return
  if (feedbackForm.outcome !== 'SUCCESS' && !feedbackForm.closeReason.trim()) {
    message.value = '未达成合作或主动退出时必须填写原因。'
    return
  }
  busy.value = true; message.value = ''
  try {
    const matchId = selected.value.id
    lastFeedback.value = await platformApi.submitMatchFeedback(selected.value, {
      rating: feedbackForm.rating ? Number(feedbackForm.rating) : null,
      outcome: feedbackForm.outcome,
      closeReason: feedbackForm.closeReason.trim() || null,
      comment: feedbackForm.comment.trim() || null,
    }, lastFeedback.value)
    feedback.value = [lastFeedback.value, ...feedback.value.filter((value) => value.id !== lastFeedback.value?.id)]
    if (!await refreshAfterSaved(matchId)) return
    prepareFeedbackForm(lastFeedback.value, selected.value?.state)
    message.value = '匹配反馈已提交，系统已保存企业评价。'
  } catch (reason) { await handleMatchActionFailure(reason, '匹配反馈提交失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

async function archiveOutcome() {
  if (!selected.value || !outcomeForm.title.trim() || !outcomeForm.summary.trim() || busy.value
    || !canArchiveOutcome(selected.value)) return
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
    if (!await refreshAfterSaved(matchId)) return
    outcomeForm.title = ''; outcomeForm.summary = ''; outcomeForm.contractAmount = ''
    message.value = '合作成果已归档。'
  } catch (reason) { await handleMatchActionFailure(reason, '成果归档失败。', selected.value?.id || '') }
  finally { busy.value = false }
}

onMounted(() => {
  invitationClockTimer = window.setInterval(() => { currentTime.value = Date.now() }, 30_000)
  void load()
})
watch(
  () => [route.query.match, route.query.demand],
  ([nextMatch, nextDemand], [previousMatch, previousDemand]) => {
    if (nextMatch === previousMatch && nextDemand === previousDemand) return
    if (nextMatch !== previousMatch) closeDetail()
    void load()
  },
)
onBeforeUnmount(() => {
  viewActive = false
  loadRequestSequence += 1
  workflowRequestSequence += 1
  if (invitationClockTimer !== null) window.clearInterval(invitationClockTimer)
})
</script>

<template>
  <div>
    <PageHeader eyebrow="ECOSYSTEM MATCHING" title="生态匹配" description="基于已审核的真实需求和在架能力，生成可确认、可反馈的匹配记录">
      <button class="secondary-button" @click="rulesOpen = true">匹配依据</button><button v-if="canGenerate" class="primary-button" @click="openGenerator">生成新一轮匹配</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="match-summary">
      <div><span>符合当前筛选</span><strong>{{ total }}</strong><small>数据库总数</small></div><div><span>本页已由协会推荐</span><strong>{{ recommendedCount }}</strong><small>本页 {{ items.length }} 条</small></div><div><span>本页双方均已确认</span><strong>{{ confirmedCount }}</strong><small>仅统计当前页</small></div>
      <div class="matching-logic"><span class="ai-chip">规则</span><p><b>记录可解释</b>每条匹配保留分数和具体推荐理由，并由协会与企业人工确认。</p></div>
    </section>
    <div class="segmented match-tabs"><button v-for="filter in MATCH_STATE_FILTERS" :key="filter.value || 'ALL'" :class="{ active: state === filter.value }" :disabled="loading || busy" @click="selectState(filter.value)">{{ filter.label }}</button></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="match-list">
      <article v-for="item in items" :key="item.id" class="match-card panel">
        <div class="match-score"><svg viewBox="0 0 44 44"><circle cx="22" cy="22" r="18"/><circle class="score-line" cx="22" cy="22" r="18" :style="{ strokeDashoffset: `${113 - (item.score ?? 0) * 1.13}` }"/></svg><div><strong>{{ item.score ?? '—' }}</strong><span>匹配度</span></div></div>
        <div class="match-demand"><span class="eyebrow">需求方 · {{ item.scene || '场景未授权' }}</span><h2>{{ item.demandTitle || '需求标题未授权' }}</h2><p>{{ item.demandCompany || '需求企业未授权' }}</p></div>
        <div class="match-arrow"><span>可解释推荐</span>→</div>
        <div class="match-supplier"><span class="eyebrow">能力供给方</span><h2>{{ item.supplierCompany || '供给企业未授权' }}</h2><p>{{ item.solution || '方案未授权' }}</p></div>
        <div class="match-actions"><StatusBadge :value="displayBusinessStatus(item.state)" /><small>{{ formatDateTime(item.updatedAt) }}</small><button class="primary-button small" @click="openDetail(item)">查看匹配详情</button></div>
        <div class="match-reasons"><b>推荐理由</b><span v-for="reason in item.reasons" :key="reason">✓ {{ reason }}</span><span v-if="!item.reasons.length">暂无理由说明</span></div>
      </article>
      <div v-if="!items.length" class="panel empty-business-state"><b>{{ state ? '当前状态下暂无匹配记录' : '暂无匹配记录' }}</b><span v-if="canGenerate">可从“生成新一轮匹配”选择当前身份有权操作的开放需求。</span><span v-else-if="hasGenerationRole">当前身份暂无有权生成匹配的开放需求。</span><span v-else>当前身份可查看已授权的匹配记录，但不能发起生成。</span></div>
      <PaginationBar v-if="total > 0" :page="page" :size="size" :total="total" :disabled="loading || busy" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="rulesOpen" class="modal-backdrop" @click.self="rulesOpen = false"><section class="panel modal-card compact-modal"><div class="modal-head"><div><span class="eyebrow">MATCH EXPLAINABILITY</span><h2>匹配依据</h2></div><button class="icon-button" @click="rulesOpen = false">×</button></div><div class="modal-copy"><p>系统从需求场景、所需能力、供给方产品/服务、资质与数据可见性中生成候选。</p><p>分数和推荐理由以后端每条记录为准，页面不伪造固定权重。</p><p>匹配不会自动对外推送：必须经协会推荐、企业确认后才进入洽谈。</p></div><div class="form-actions"><button class="primary-button" @click="rulesOpen = false">我知道了</button></div></section></div>
    <div v-if="generatorOpen" class="modal-backdrop" @click.self="generatorOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="generate()"><div class="modal-head"><div><span class="eyebrow">GENERATE MATCHES</span><h2>选择真实需求</h2></div><button type="button" class="icon-button" @click="generatorOpen = false">×</button></div><div v-if="message" class="save-message modal-message" aria-live="polite">{{ message }}</div><div class="form-grid modal-form"><label class="form-span-2"><span>需求 *</span><select v-model="selectedDemandId" required><option value="" disabled>请选择</option><option v-for="demand in demands" :key="demand.id" :value="demand.id">{{ demand.title }} · {{ demand.enterpriseName || '企业名称未授权' }}</option></select></label><p v-if="!demands.length" class="form-span-2 workflow-note">当前身份下没有可生成匹配的已开放需求。</p></div><div class="form-actions"><button type="button" class="secondary-button" @click="generatorOpen = false">取消</button><button class="primary-button" :disabled="busy || !demands.length">{{ busy ? '正在匹配…' : '生成并保存匹配' }}</button></div></form></div>
    <div v-if="selected" class="modal-backdrop" @click.self="closeDetail">
      <section class="panel modal-card match-detail-modal">
        <div class="modal-head">
          <div><span class="eyebrow">MATCH DETAIL</span><h2>{{ selected.demandTitle || '需求标题未授权' }}</h2></div>
          <button class="icon-button" @click="closeDetail">×</button>
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
          <input v-model="closeReason" maxlength="1000" placeholder="关闭时必须填写原因" />
          <button class="text-button danger-text" :disabled="!closeReason.trim() || busy" @click="closeMatch">关闭匹配</button>
        </div>
        <div class="form-actions match-state-actions">
          <button v-if="canRecommend(selected)" class="primary-button" :disabled="busy" @click="transition('recommend')">协会推荐</button>
          <button v-if="canConfirm(selected)" class="primary-button" :disabled="busy" @click="transition('confirm')">确认本方意向</button>
          <RouterLink v-if="!workflowLoading && canOpenCollaboration(selected) && ['NEGOTIATING', 'OUTCOME_PENDING', 'ARCHIVED'].includes(selected.state || '')" class="primary-button" :to="`/collaborations?match=${selected.id}`">进入协作事项</RouterLink>
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
          <div v-if="!workflowLoading && workflowError" class="workflow-empty" role="alert">{{ workflowError }} <button type="button" class="secondary-button small" @click="loadWorkflow(selected)">重新加载</button></div>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">INVITATIONS</span><h3>定向邀请与应答</h3></div><small>{{ invitations.length }} 条记录</small></div>
            <div v-if="workflowSectionErrors.invitations" class="workflow-empty" role="alert">{{ workflowSectionErrors.invitations }} <button type="button" class="secondary-button small" @click="loadWorkflow(selected)">重试</button></div>
            <div v-else-if="invitations.length" class="workflow-record-list">
              <article v-for="invitation in invitations" :key="invitation.id" class="workflow-record">
                <div class="workflow-record-head"><StatusBadge :value="displayBusinessStatus(invitationStatus(invitation))" /><small>{{ formatDateTime(invitation.createdAt) }}</small></div>
                <p>{{ invitation.message || '邀请方未附加说明。' }}</p>
                <small>类型：{{ invitation.invitationType === 'ASSOCIATION_RECOMMENDATION' ? '协会推荐' : '企业邀请' }}<template v-if="invitation.expiresAt"> · 截止 {{ formatDateTime(invitation.expiresAt) }}</template></small>
                <p v-if="invitation.responseComment" class="workflow-response">应答说明：{{ invitation.responseComment }}</p>
                <p v-if="invitationStatus(invitation) === 'EXPIRED'" class="workflow-response">邀请已超过应答截止时间，不能再接受或拒绝。</p>
                <form v-if="canViewInvitationResponse(invitation)" class="workflow-inline-form" @submit.prevent="respondInvitation(invitation, true)">
                  <input v-model="invitationResponseComment" maxlength="1000" :disabled="busy || !canRespondInvitation(invitation)" placeholder="接受时可选；拒绝时必须填写原因" />
                  <button type="button" class="secondary-button small" :disabled="busy || !canRespondInvitation(invitation) || !invitationResponseComment.trim()" @click="respondInvitation(invitation, false)">拒绝</button>
                  <button class="primary-button small" :disabled="busy || !canRespondInvitation(invitation)">接受</button>
                </form>
              </article>
            </div>
            <div v-else class="workflow-empty">尚未发送定向邀请。</div>
            <form v-if="!workflowSectionErrors.invitations && canInvite(selected)" class="workflow-form" @submit.prevent="sendInvitation">
              <label class="form-span-2"><span>邀请说明</span><textarea v-model="invitationMessage" maxlength="2000" rows="2" placeholder="说明合作方向、预期或联系人"></textarea></label>
              <label><span>应答截止时间</span><input v-model="invitationExpiresAt" type="datetime-local" :min="minimumConsentExpiry" /></label>
              <div class="workflow-submit"><button class="primary-button small" :disabled="busy">发送定向邀请</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">NEGOTIATIONS</span><h3>洽谈进度</h3></div><small>{{ negotiations.length }} 条记录</small></div>
            <div v-if="workflowSectionErrors.negotiations" class="workflow-empty" role="alert">{{ workflowSectionErrors.negotiations }} <button type="button" class="secondary-button small" @click="loadWorkflow(selected)">重试</button></div>
            <div v-else-if="negotiations.length" class="workflow-record-list">
              <article v-for="record in negotiations" :key="record.id" class="workflow-record">
                <div class="workflow-record-head"><b>{{ displayBusinessStatus(record.stage) }}</b><small>{{ formatDateTime(record.createdAt) }}</small></div>
                <p>{{ record.summary }}</p>
                <small v-if="record.nextAction">下一步：{{ record.nextAction }}<template v-if="record.nextActionAt"> · {{ formatDateTime(record.nextActionAt) }}</template></small>
              </article>
            </div>
            <div v-else class="workflow-empty">尚无洽谈记录。</div>
            <form v-if="!workflowSectionErrors.negotiations && canNegotiate(selected) && negotiationStageOptions.length" class="workflow-form" @submit.prevent="addNegotiation">
              <label><span>洽谈阶段 *</span><select v-model="negotiationForm.stage" required><option v-for="option in negotiationStageOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
              <label><span>下次行动时间</span><input v-model="negotiationForm.nextActionAt" type="datetime-local" /></label>
              <label class="form-span-2"><span>进展摘要 *</span><textarea v-model="negotiationForm.summary" :maxlength="negotiationForm.stage === 'TERMINATED' ? 1000 : 5000" rows="2" required></textarea></label>
              <label class="form-span-2"><span>下一步行动</span><input v-model="negotiationForm.nextAction" maxlength="1000" placeholder="如：安排现场勘查" /></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">保存洽谈记录</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">FEEDBACK</span><h3>企业匹配反馈</h3></div><small>{{ feedback.length }}/2 方已反馈</small></div>
            <div v-if="workflowSectionErrors.feedback" class="workflow-empty" role="alert">{{ workflowSectionErrors.feedback }} <button type="button" class="secondary-button small" @click="loadWorkflow(selected)">重试</button></div>
            <div v-else-if="feedback.length" class="workflow-record-list">
              <article v-for="record in feedback" :key="record.id" class="workflow-record">
                <div class="workflow-record-head"><StatusBadge :value="displayBusinessStatus(record.outcome)" /><small>{{ formatDateTime(record.updatedAt || record.submittedAt) }}</small></div>
                <p>反馈企业：{{ record.enterpriseId }}<template v-if="record.rating"> · {{ record.rating }} 分</template></p>
                <p v-if="record.comment">{{ record.comment }}</p><small v-if="record.closeReason">原因：{{ record.closeReason }}</small>
              </article>
            </div>
            <div v-else class="workflow-empty">参与企业尚未提交反馈。</div>
            <form v-if="!workflowSectionErrors.feedback && canSubmitFeedback(selected)" class="workflow-form" @submit.prevent="submitFeedback">
              <label><span>结果 *</span><select v-model="feedbackForm.outcome" required><option v-if="selected.state === 'OUTCOME_PENDING'" value="SUCCESS">已达成合作</option><template v-else><option value="NO_DEAL">未达成合作</option><option value="WITHDRAWN">主动退出</option></template></select></label>
              <label><span>评分</span><select v-model="feedbackForm.rating"><option value="">暂不评分</option><option v-for="score in 5" :key="score" :value="String(score)">{{ score }} 分</option></select></label>
              <label class="form-span-2"><span>反馈说明</span><textarea v-model="feedbackForm.comment" maxlength="3000" rows="2"></textarea></label>
              <label v-if="feedbackForm.outcome !== 'SUCCESS'" class="form-span-2"><span>未达成原因 *</span><input v-model="feedbackForm.closeReason" maxlength="1000" required /></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">提交企业反馈</button></div>
            </form>
          </section>

          <section v-if="!workflowLoading" class="workflow-section">
            <div class="workflow-title"><div><span class="eyebrow">OUTCOMES</span><h3>合作成果归档</h3></div><small>{{ outcomes.length }} 项成果</small></div>
            <div v-if="workflowSectionErrors.outcomes" class="workflow-empty" role="alert">{{ workflowSectionErrors.outcomes }} <button type="button" class="secondary-button small" @click="loadWorkflow(selected)">重试</button></div>
            <div v-else-if="outcomes.length" class="workflow-record-list">
              <article v-for="outcome in outcomes" :key="outcome.id" class="workflow-record">
                <div class="workflow-record-head"><b>{{ outcome.title }}</b><small>{{ formatDateTime(outcome.archivedAt) }}</small></div>
                <p>{{ outcome.summary }}</p>
                <small>{{ displayBusinessStatus(outcome.resultType) }} · {{ displayBusinessStatus(outcome.visibility) }}<template v-if="outcome.contractAmount !== null"> · 合同金额 {{ outcome.contractAmount.toLocaleString('zh-CN') }} 元</template></small>
              </article>
            </div>
            <div v-else class="workflow-empty">尚无已归档成果。</div>
            <form v-if="!workflowSectionErrors.outcomes && canArchiveOutcome(selected)" class="workflow-form" @submit.prevent="archiveOutcome">
              <label><span>成果标题 *</span><input v-model="outcomeForm.title" maxlength="300" required /></label>
              <label><span>合同金额（元）</span><input v-model="outcomeForm.contractAmount" type="number" min="0" step="0.01" /></label>
              <label class="form-span-2"><span>成果摘要 *</span><textarea v-model="outcomeForm.summary" maxlength="5000" rows="3" required></textarea></label>
              <label><span>成果类型 *</span><select v-model="outcomeForm.resultType" required><option value="COOPERATION">合作落地</option><option value="CONTRACT">合同签订</option><option value="PILOT">试点项目</option><option value="TECHNICAL_RESULT">技术成果</option></select></label>
              <label><span>可见范围 *</span><select v-model="outcomeForm.visibility" required><option value="PRIVATE">仅归档人</option><option value="ENTERPRISES">参与企业</option><option value="ASSOCIATION">协会</option><option value="PARTNERS">合作协会</option><option value="PUBLIC">公开</option></select></label>
              <div class="workflow-submit form-span-2"><button class="primary-button small" :disabled="busy">归档合作成果</button></div>
            </form>
            <p v-else-if="!workflowSectionErrors.outcomes && selected.state === 'OUTCOME_PENDING'" class="workflow-note">仅合同签署后进入成果待归档阶段，且双方均提交成功反馈，具有归档权限的一方才可归档成果。</p>
          </section>
        </div>
      </section>
    </div>
  </div>
</template>
