<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type {
  AssociationAccessRequest,
  AssociationConsent,
  AssociationRecommendation,
  AssociationRelationship,
  AssociationSharePolicy,
  AssociationSharePolicyPayload,
  AssociationShareResourceType,
} from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime } from './business-form'

type AccessAction = 'APPROVE' | 'REJECT' | 'CANCEL'
type RelationshipAction = 'ACTIVATE' | 'SUSPEND' | 'REVOKE'
type RecommendationAction = 'APPROVE' | 'REJECT'

interface ShareFieldOption {
  value: string
  label: string
  required?: boolean
}

const shareFieldOptions: Record<AssociationShareResourceType, ShareFieldOption[]> = {
  MEMBER: [
    { value: 'name', label: '企业名称', required: true },
    { value: 'category', label: '企业类别' },
    { value: 'address', label: '地址' },
    { value: 'introduction', label: '企业简介' },
    { value: 'capabilities', label: '能力标签' },
    { value: 'products', label: '产品概览' },
    { value: 'cooperationNeeds', label: '合作需求' },
  ],
  PRODUCT: [
    { value: 'enterpriseName', label: '企业名称' },
    { value: 'name', label: '产品名称', required: true },
    { value: 'description', label: '产品说明' },
    { value: 'scenarios', label: '应用场景' },
    { value: 'qualifications', label: '资质证书' },
  ],
  SERVICE: [
    { value: 'enterpriseName', label: '企业名称' },
    { value: 'name', label: '服务名称', required: true },
    { value: 'description', label: '服务说明' },
    { value: 'scenarios', label: '应用场景' },
    { value: 'qualifications', label: '资质证书' },
  ],
  DEMAND: [
    { value: 'enterpriseName', label: '企业名称' },
    { value: 'title', label: '需求标题', required: true },
    { value: 'description', label: '需求说明' },
    { value: 'scenarios', label: '应用场景' },
    { value: 'requiredCapabilities', label: '所需能力' },
    { value: 'budgetMin', label: '预算下限' },
    { value: 'budgetMax', label: '预算上限' },
    { value: 'responseDeadline', label: '响应截止时间' },
  ],
  MATCH: [
    { value: 'demandCompany', label: '需求企业' },
    { value: 'demandTitle', label: '需求标题' },
    { value: 'scene', label: '应用场景' },
    { value: 'supplierCompany', label: '供给企业' },
    { value: 'solution', label: '解决方案' },
    { value: 'score', label: '匹配分数' },
    { value: 'reasons', label: '匹配依据' },
    { value: 'state', label: '匹配状态' },
    { value: 'outcomes', label: '合作成果（不含合同金额及内部操作人）' },
  ],
}

const resourceTypeLabels: Record<AssociationShareResourceType, string> = {
  MEMBER: '会员企业', PRODUCT: '产品', SERVICE: '服务', DEMAND: '需求', MATCH: '匹配结果',
}

const auth = useAuth()
const requests = ref<AssociationAccessRequest[]>([])
const relationships = ref<AssociationRelationship[]>([])
const relationshipRows = ref<AssociationRelationship[]>([])
const policies = ref<AssociationSharePolicy[]>([])
const consents = ref<AssociationConsent[]>([])
const recommendations = ref<AssociationRecommendation[]>([])
const requestPage = ref(0); const requestSize = ref(20); const requestTotal = ref(0)
const relationshipPage = ref(0); const relationshipSize = ref(20); const relationshipTotal = ref(0)
const policyPage = ref(0); const policySize = ref(20); const policyTotal = ref(0)
const consentPage = ref(0); const consentSize = ref(20); const consentTotal = ref(0)
const recommendationPage = ref(0); const recommendationSize = ref(20); const recommendationTotal = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')

const requestFormOpen = ref(false)
const requestForm = reactive({ targetAssociationId: '', reason: '' })
const accessActionOpen = ref(false)
const selectedRequest = ref<AssociationAccessRequest | null>(null)
const accessAction = ref<AccessAction>('APPROVE')
const accessForm = reactive({ comment: '', expiresAt: '', allowMemberData: false })

const relationshipActionOpen = ref(false)
const selectedRelationship = ref<AssociationRelationship | null>(null)
const relationshipAction = ref<RelationshipAction>('SUSPEND')
const relationshipForm = reactive({ reason: '' })

const policyFormOpen = ref(false)
const selectedPolicy = ref<AssociationSharePolicy | null>(null)
const policyForm = reactive<{
  targetAssociationId: string
  resourceType: AssociationShareResourceType
  visibleFields: string[]
  validFrom: string
  expiresAt: string
}>({ targetAssociationId: '', resourceType: 'MEMBER', visibleFields: ['name'], validFrom: '', expiresAt: '' })

const recommendationFormOpen = ref(false)
const recommendationForm = reactive({ targetAssociationId: '', demandId: '', matchId: '', summary: '' })
const recommendationReviewOpen = ref(false)
const selectedRecommendation = ref<AssociationRecommendation | null>(null)
const recommendationAction = ref<RecommendationAction>('APPROVE')
const recommendationComment = ref('')

const currentAssociationId = computed(() => auth.user.value?.associationId || null)
const canManage = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const currentShareFields = computed(() => shareFieldOptions[policyForm.resourceType])
const minimumExpiry = toLocalInput(new Date(Date.now() + 60_000))

function toLocalInput(value: string | Date | null | undefined): string {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function futureLocalInput(days: number): string {
  return toLocalInput(new Date(Date.now() + days * 86_400_000))
}

function toIso(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

function isExpired(expiresAt: string | null): boolean {
  return Boolean(expiresAt && new Date(expiresAt).getTime() <= Date.now())
}

function relationshipState(item: AssociationRelationship): string {
  if (!['REVOKED', 'EXPIRED'].includes(item.status) && isExpired(item.expiresAt)) return 'EXPIRED'
  return item.status
}

function hasActiveRelationship(sourceAssociationId: string, targetAssociationId: string): boolean {
  return relationships.value.some((item) => {
    const samePair = (item.sourceAssociationId === sourceAssociationId && item.targetAssociationId === targetAssociationId)
      || (item.sourceAssociationId === targetAssociationId && item.targetAssociationId === sourceAssociationId)
    return samePair && relationshipState(item) === 'ACTIVE' && item.allowMemberData
  })
}

function timedStatus(status: string, expiresAt: string | null): string {
  if (status === 'ACTIVE' && isExpired(expiresAt)) return 'EXPIRED'
  return status
}

function isRelationshipParticipant(item: AssociationRelationship): boolean {
  return Boolean(currentAssociationId.value && (
    item.sourceAssociationId === currentAssociationId.value || item.targetAssociationId === currentAssociationId.value
  ))
}

function otherAssociation(item: AssociationRelationship): string {
  if (item.sourceAssociationId === currentAssociationId.value) return item.targetAssociationId
  if (item.targetAssociationId === currentAssociationId.value) return item.sourceAssociationId
  return `${item.sourceAssociationId} ↔ ${item.targetAssociationId}`
}

const activePartnerIds = computed(() => [...new Set(relationships.value
  .filter((item) => relationshipState(item) === 'ACTIVE' && item.allowMemberData && isRelationshipParticipant(item))
  .map(otherAssociation))])

function canReviewRequest(item: AssociationAccessRequest): boolean {
  return canManage.value && item.status === 'PENDING' && item.targetAssociationId === currentAssociationId.value
}

function canCancelRequest(item: AssociationAccessRequest): boolean {
  return canManage.value && item.status === 'PENDING' && item.applicantAssociationId === currentAssociationId.value
}

function canManagePolicy(item: AssociationSharePolicy): boolean {
  return canManage.value && item.sourceAssociationId === currentAssociationId.value
}

function canEditPolicy(item: AssociationSharePolicy): boolean {
  return canManagePolicy(item) && hasActiveRelationship(item.sourceAssociationId, item.targetAssociationId)
}

function canTogglePolicy(item: AssociationSharePolicy): boolean {
  if (!canManagePolicy(item)) return false
  if (item.status === 'ACTIVE') return true
  const policyStillValid = !isExpired(item.expiresAt)
  return policyStillValid && hasActiveRelationship(item.sourceAssociationId, item.targetAssociationId)
}

function canReviewRecommendation(item: AssociationRecommendation): boolean {
  return canManage.value && item.status === 'PENDING_REVIEW'
    && item.targetAssociationId === currentAssociationId.value
    && hasActiveRelationship(item.sourceAssociationId, item.targetAssociationId)
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [requestResult, relationshipValues, relationshipResult, policyResult, consentResult, recommendationResult] = await Promise.all([
      platformApi.associationAccessRequestPage(requestPage.value, requestSize.value),
      platformApi.associationRelationships(),
      platformApi.associationRelationshipPage(relationshipPage.value, relationshipSize.value),
      platformApi.associationSharePolicyPage(policyPage.value, policySize.value),
      platformApi.associationConsentPage(consentPage.value, consentSize.value),
      platformApi.associationRecommendationPage(recommendationPage.value, recommendationSize.value),
    ])
    requests.value = requestResult.items; requestPage.value = requestResult.page; requestSize.value = requestResult.size; requestTotal.value = requestResult.total
    relationships.value = relationshipValues
    relationshipRows.value = relationshipResult.items; relationshipPage.value = relationshipResult.page; relationshipSize.value = relationshipResult.size; relationshipTotal.value = relationshipResult.total
    policies.value = policyResult.items; policyPage.value = policyResult.page; policySize.value = policyResult.size; policyTotal.value = policyResult.total
    consents.value = consentResult.items; consentPage.value = consentResult.page; consentSize.value = consentResult.size; consentTotal.value = consentResult.total
    recommendations.value = recommendationResult.items; recommendationPage.value = recommendationResult.page; recommendationSize.value = recommendationResult.size; recommendationTotal.value = recommendationResult.total
  } catch (reason) {
    error.value = safePageResourceError(reason)
  } finally {
    loading.value = false
  }
}

async function createRequest() {
  if (busy.value) return
  if (!currentAssociationId.value) {
    message.value = '请先选择要管理的协会上下文。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.createAssociationAccessRequest(requestForm.targetAssociationId.trim(), requestForm.reason)
    requests.value = [saved, ...requests.value]
    requestFormOpen.value = false
    Object.assign(requestForm, { targetAssociationId: '', reason: '' })
    message.value = '接入申请已提交，等待目标协会审批。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '接入申请提交失败，请核对目标协会及现有关系。')
  } finally {
    busy.value = false
  }
}

function openAccessAction(item: AssociationAccessRequest, action: AccessAction) {
  selectedRequest.value = item
  accessAction.value = action
  accessForm.comment = ''
  accessForm.expiresAt = action === 'APPROVE' ? futureLocalInput(365) : ''
  accessForm.allowMemberData = false
  accessActionOpen.value = true
}

async function submitAccessAction() {
  const item = selectedRequest.value
  if (!item || busy.value) return
  const approved = accessAction.value === 'APPROVE'
  const expiry = approved ? toIso(accessForm.expiresAt) : null
  if (approved && (!expiry || new Date(expiry).getTime() <= Date.now())) {
    message.value = '批准申请时必须设置一个未来的授权截止时间。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    if (accessAction.value === 'CANCEL') {
      await platformApi.cancelAssociationAccessRequest(item, accessForm.comment)
      message.value = '接入申请已取消并保留操作记录。'
    } else {
      await platformApi.reviewAssociationAccessRequest(item, {
        approved, comment: accessForm.comment, relationshipExpiresAt: expiry,
        allowMemberData: accessForm.allowMemberData,
      })
      message.value = approved ? '申请已批准，双边关系和授权期限已生效。' : '申请已驳回并保留审批意见。'
    }
    accessActionOpen.value = false
    await load()
  } catch (reason) {
    message.value = apiActionMessage(reason, '接入申请处理失败。')
  } finally {
    busy.value = false
  }
}

function openRelationshipAction(item: AssociationRelationship, action: RelationshipAction) {
  selectedRelationship.value = item
  relationshipAction.value = action
  relationshipForm.reason = ''
  relationshipActionOpen.value = true
}

async function submitRelationshipAction() {
  const item = selectedRelationship.value
  if (!item || busy.value) return
  if (relationshipAction.value !== 'ACTIVATE' && !relationshipForm.reason.trim()) {
    message.value = '暂停或撤销关系必须填写原因，以便审计追溯。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.changeAssociationRelationship(item, relationshipAction.value, relationshipForm.reason)
    relationships.value = relationships.value.map((value) =>
      value.sourceAssociationId === saved.sourceAssociationId && value.targetAssociationId === saved.targetAssociationId ? saved : value)
    relationshipActionOpen.value = false
    message.value = relationshipAction.value === 'ACTIVATE'
      ? '关系已恢复，原授权截止时间保持不变。'
      : '协会关系状态已更新，跨协会访问范围已同步收紧。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '协会关系更新失败。')
  } finally {
    busy.value = false
  }
}

function requiredFields(type: AssociationShareResourceType): string[] {
  return shareFieldOptions[type].filter((item) => item.required).map((item) => item.value)
}

function resetPolicyFields() {
  policyForm.visibleFields = requiredFields(policyForm.resourceType)
}

function openNewPolicy() {
  selectedPolicy.value = null
  Object.assign(policyForm, {
    targetAssociationId: activePartnerIds.value[0] || '', resourceType: 'MEMBER' as AssociationShareResourceType,
    visibleFields: ['name'], validFrom: toLocalInput(new Date()), expiresAt: futureLocalInput(365),
  })
  policyFormOpen.value = true
}

function openEditPolicy(item: AssociationSharePolicy) {
  selectedPolicy.value = item
  const allowedFields = new Set(shareFieldOptions[item.resourceType].map((field) => field.value))
  const visibleFields = [...new Set([
    ...requiredFields(item.resourceType),
    ...item.visibleFields.filter((field) => allowedFields.has(field)),
  ])]
  Object.assign(policyForm, {
    targetAssociationId: item.targetAssociationId, resourceType: item.resourceType,
    visibleFields, validFrom: toLocalInput(item.validFrom), expiresAt: toLocalInput(item.expiresAt),
  })
  policyFormOpen.value = true
}

async function savePolicy() {
  if (busy.value) return
  const allowed = new Set(shareFieldOptions[policyForm.resourceType].map((item) => item.value))
  const selected = [...new Set(policyForm.visibleFields)]
  const missingRequired = requiredFields(policyForm.resourceType).some((field) => !selected.includes(field))
  if (!selected.length || missingRequired || selected.some((field) => !allowed.has(field))) {
    message.value = '共享字段不符合该资源类型的白名单要求，请重新选择。'
    return
  }
  const validFrom = toIso(policyForm.validFrom)
  const expiresAt = toIso(policyForm.expiresAt)
  if (!validFrom || !expiresAt || new Date(expiresAt).getTime() <= new Date(validFrom).getTime()) {
    message.value = '共享策略必须设置有效的起止时间，且截止时间晚于生效时间。'
    return
  }
  const payload: AssociationSharePolicyPayload = {
    sourceAssociationId: currentAssociationId.value, targetAssociationId: policyForm.targetAssociationId,
    resourceType: policyForm.resourceType, visibleFields: selected, validFrom, expiresAt,
    status: selectedPolicy.value?.status || 'ACTIVE',
  }
  busy.value = true
  message.value = ''
  try {
    const saved = selectedPolicy.value
      ? await platformApi.updateAssociationSharePolicy(selectedPolicy.value, payload)
      : await platformApi.createAssociationSharePolicy(payload)
    policies.value = selectedPolicy.value
      ? policies.value.map((item) => item.id === saved.id ? saved : item)
      : [saved, ...policies.value]
    policyFormOpen.value = false
    message.value = '字段共享策略已保存，未勾选的业务字段会在接口响应中被裁剪。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '共享策略保存失败，请确认关系有效且字段白名单正确。')
  } finally {
    busy.value = false
  }
}

async function togglePolicy(item: AssociationSharePolicy) {
  if (busy.value) return
  busy.value = true
  message.value = ''
  const status = item.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
  try {
    const saved = await platformApi.changeAssociationSharePolicyStatus(item, status)
    policies.value = policies.value.map((value) => value.id === saved.id ? saved : value)
    message.value = status === 'ACTIVE' ? '共享策略已恢复。' : '共享策略已暂停，相关跨协会字段访问已停止。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '共享策略状态更新失败。')
  } finally {
    busy.value = false
  }
}

function openNewRecommendation() {
  Object.assign(recommendationForm, {
    targetAssociationId: activePartnerIds.value[0] || '', demandId: '', matchId: '', summary: '',
  })
  recommendationFormOpen.value = true
}

async function createRecommendation() {
  if (busy.value) return
  const demandId = recommendationForm.demandId.trim() || null
  const matchId = recommendationForm.matchId.trim() || null
  if (!demandId && !matchId) {
    message.value = '定向推荐必须关联一个真实需求或匹配记录。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.createAssociationRecommendation(
      recommendationForm.targetAssociationId, demandId, matchId, recommendationForm.summary,
    )
    recommendations.value = [saved, ...recommendations.value]
    recommendationFormOpen.value = false
    message.value = '跨协会推荐已提交，等待目标协会确认。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '推荐提交失败，请确认资源归属和协会关系。')
  } finally {
    busy.value = false
  }
}

function openRecommendationReview(item: AssociationRecommendation, action: RecommendationAction) {
  selectedRecommendation.value = item
  recommendationAction.value = action
  recommendationComment.value = ''
  recommendationReviewOpen.value = true
}

async function submitRecommendationReview() {
  const item = selectedRecommendation.value
  if (!item || busy.value) return
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.reviewAssociationRecommendation(
      item, recommendationAction.value === 'APPROVE', recommendationComment.value,
    )
    recommendations.value = recommendations.value.map((value) => value.id === saved.id ? saved : value)
    recommendationReviewOpen.value = false
    message.value = recommendationAction.value === 'APPROVE' ? '推荐已确认，可进入后续协作流程。' : '推荐已退回。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '推荐审批失败。')
  } finally {
    busy.value = false
  }
}

function changeRequestPage(value: number) { requestPage.value = value; void load() }
function resizeRequestPage(value: number) { requestSize.value = value; requestPage.value = 0; void load() }
function changeRelationshipPage(value: number) { relationshipPage.value = value; void load() }
function resizeRelationshipPage(value: number) { relationshipSize.value = value; relationshipPage.value = 0; void load() }
function changePolicyPage(value: number) { policyPage.value = value; void load() }
function resizePolicyPage(value: number) { policySize.value = value; policyPage.value = 0; void load() }
function changeConsentPage(value: number) { consentPage.value = value; void load() }
function resizeConsentPage(value: number) { consentSize.value = value; consentPage.value = 0; void load() }
function changeRecommendationPage(value: number) { recommendationPage.value = value; void load() }
function resizeRecommendationPage(value: number) { recommendationSize.value = value; recommendationPage.value = 0; void load() }

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="FEDERATION" title="友好协会" description="接入审批、关系授权、字段共享、企业同意和定向推荐均使用真实持久化数据并全程留痕">
      <button v-if="canManage && currentAssociationId" class="primary-button" @click="requestFormOpen = true">+ 申请接入协会</button>
    </PageHeader>

    <div v-if="!currentAssociationId && canManage" class="notice-banner warning">系统管理员需先在顶部选择协会上下文，才能代表该协会发起或审批操作。</div>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />

    <template v-else>
      <section class="panel business-section">
        <div class="panel-header">
          <div><h2>协会关系</h2><p>暂停方可以恢复原关系；撤销或到期后必须重新申请并由对方审批，不允许单方延长授权。</p></div>
          <button class="text-button" @click="load">刷新</button>
        </div>
        <div v-if="relationshipRows.length" class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>合作协会</th><th>状态</th><th>会员数据</th><th>授权截止</th><th>状态说明</th><th></th></tr></thead>
            <tbody><tr v-for="item in relationshipRows" :key="`${item.sourceAssociationId}-${item.targetAssociationId}`">
              <td>{{ otherAssociation(item) }}</td>
              <td><StatusBadge :value="displayBusinessStatus(relationshipState(item))" /></td>
              <td>{{ item.allowMemberData ? '允许' : '不允许' }}</td>
              <td>{{ formatDateTime(item.expiresAt) }}</td>
              <td>
                <span v-if="relationshipState(item) === 'EXPIRED'">授权已于 {{ formatDateTime(item.expiresAt) }} 到期</span>
                <span v-else-if="item.revokedAt">{{ item.revokeReason || '已撤销' }} · {{ formatDateTime(item.revokedAt) }}</span>
                <span v-else-if="item.suspendedAt">由 {{ item.suspendedByAssociationId }} 暂停 · {{ formatDateTime(item.suspendedAt) }}</span>
                <span v-else>版本 {{ item.version }} · 更新于 {{ formatDateTime(item.updatedAt) }}</span>
              </td>
              <td><div v-if="canManage && isRelationshipParticipant(item)" class="inline-actions">
                <button v-if="relationshipState(item) === 'SUSPENDED' && item.suspendedByAssociationId === currentAssociationId" class="text-button" @click="openRelationshipAction(item, 'ACTIVATE')">恢复</button>
                <button v-if="relationshipState(item) === 'ACTIVE'" class="text-button" @click="openRelationshipAction(item, 'SUSPEND')">暂停</button>
                <button v-if="!['REVOKED', 'EXPIRED'].includes(relationshipState(item))" class="text-button danger-text" @click="openRelationshipAction(item, 'REVOKE')">撤销</button>
                <span v-if="['REVOKED', 'EXPIRED'].includes(relationshipState(item))">请重新申请</span>
              </div></td>
            </tr></tbody>
          </table>
        </div>
        <div v-else class="empty-business-state"><b>暂无友好协会关系</b><span>双边审批通过后，关系会显示在这里。</span></div>
        <PaginationBar :page="relationshipPage" :size="relationshipSize" :total="relationshipTotal" :disabled="loading" @change="changeRelationshipPage" @resize="resizeRelationshipPage" />
      </section>

      <section class="panel business-section">
        <div class="panel-header"><div><h2>接入申请</h2><p>目标协会负责审批，申请协会可在待处理阶段取消；批准时必须明确授权期限。</p></div></div>
        <div v-if="requests.length" class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>申请协会</th><th>目标协会</th><th>申请原因</th><th>状态</th><th>申请时间</th><th>审批/取消意见</th><th></th></tr></thead>
            <tbody><tr v-for="item in requests" :key="item.id">
              <td>{{ item.applicantAssociationId }}</td><td>{{ item.targetAssociationId }}</td><td>{{ item.reason || '—' }}</td>
              <td><StatusBadge :value="displayBusinessStatus(item.status)" /></td><td>{{ formatDateTime(item.requestedAt) }}</td><td>{{ item.reviewComment || '—' }}</td>
              <td><div class="inline-actions">
                <button v-if="canCancelRequest(item)" class="text-button danger-text" @click="openAccessAction(item, 'CANCEL')">取消申请</button>
                <button v-if="canReviewRequest(item)" class="text-button danger-text" @click="openAccessAction(item, 'REJECT')">驳回</button>
                <button v-if="canReviewRequest(item)" class="primary-button small" @click="openAccessAction(item, 'APPROVE')">批准</button>
              </div></td>
            </tr></tbody>
          </table>
        </div>
        <div v-else class="empty-business-state"><b>暂无接入申请</b></div>
        <PaginationBar :page="requestPage" :size="requestSize" :total="requestTotal" :disabled="loading" @change="changeRequestPage" @resize="resizeRequestPage" />
      </section>

      <section class="panel business-section">
        <div class="panel-header">
          <div><h2>字段共享策略</h2><p>只有有效关系、有效策略和企业逐项同意同时成立时才开放；联系人和统一信用代码不在可选白名单内。</p></div>
          <button v-if="canManage && activePartnerIds.length" class="secondary-button" @click="openNewPolicy">+ 新建策略</button>
        </div>
        <div v-if="policies.length" class="data-table-wrap"><table class="data-table">
          <thead><tr><th>共享方向</th><th>资源</th><th>可见字段</th><th>状态</th><th>有效期</th><th></th></tr></thead>
          <tbody><tr v-for="item in policies" :key="item.id">
            <td>{{ item.sourceAssociationId }} → {{ item.targetAssociationId }}</td><td>{{ resourceTypeLabels[item.resourceType] }}</td>
            <td>{{ item.visibleFields.join('、') }}</td><td><StatusBadge :value="displayBusinessStatus(timedStatus(item.status, item.expiresAt))" /></td>
            <td>{{ formatDateTime(item.validFrom) }} — {{ formatDateTime(item.expiresAt) }}</td>
            <td><div v-if="canManagePolicy(item)" class="inline-actions"><button v-if="canEditPolicy(item)" class="text-button" @click="openEditPolicy(item)">编辑</button><button v-if="canTogglePolicy(item)" class="text-button" :class="{ 'danger-text': item.status === 'ACTIVE' }" @click="togglePolicy(item)">{{ item.status === 'ACTIVE' ? '暂停' : '恢复' }}</button><span v-if="item.status === 'SUSPENDED' && isExpired(item.expiresAt)">请先编辑期限</span><span v-else-if="!canEditPolicy(item) && !canTogglePolicy(item)">关系非活动</span></div></td>
          </tr></tbody>
        </table></div>
        <div v-else class="empty-business-state"><b>暂无字段共享策略</b><span>先建立有效关系，再由数据所属协会按字段授权。</span></div>
        <PaginationBar :page="policyPage" :size="policySize" :total="policyTotal" :disabled="loading" @change="changePolicyPage" @resize="resizePolicyPage" />
      </section>

      <section class="panel business-section">
        <div class="panel-header"><div><h2>企业共享同意</h2><p>协会只能查看授权台账；企业必须针对具体资源逐项授权，撤销后立即失效。</p></div></div>
        <div v-if="consents.length" class="data-table-wrap"><table class="data-table">
          <thead><tr><th>企业</th><th>目标协会</th><th>资源类型</th><th>资源 ID</th><th>状态</th><th>授权截止</th><th>授权/撤销时间</th></tr></thead>
          <tbody><tr v-for="item in consents" :key="item.id">
            <td>{{ item.enterpriseId }}</td><td>{{ item.targetAssociationId }}</td><td>{{ resourceTypeLabels[item.resourceType] }}</td><td>{{ item.resourceId }}</td>
            <td><StatusBadge :value="displayBusinessStatus(timedStatus(item.status, item.expiresAt))" /></td><td>{{ formatDateTime(item.expiresAt) }}</td>
            <td>{{ item.revokedAt ? `撤销于 ${formatDateTime(item.revokedAt)}` : `授权于 ${formatDateTime(item.createdAt)}` }}</td>
          </tr></tbody>
        </table></div>
        <div v-else class="empty-business-state"><b>暂无企业共享同意记录</b><span>企业对具体产品、服务、需求或会员资料授权后会显示在这里。</span></div>
        <PaginationBar :page="consentPage" :size="consentSize" :total="consentTotal" :disabled="loading" @change="changeConsentPage" @resize="resizeConsentPage" />
      </section>

      <section class="panel business-section">
        <div class="panel-header">
          <div><h2>跨协会定向推荐</h2><p>推荐必须关联真实需求或匹配记录，由目标协会确认后再进入协作流程。</p></div>
          <button v-if="canManage && activePartnerIds.length" class="secondary-button" @click="openNewRecommendation">+ 发起推荐</button>
        </div>
        <div v-if="recommendations.length" class="data-table-wrap"><table class="data-table">
          <thead><tr><th>推荐方向</th><th>关联资源</th><th>推荐说明</th><th>状态</th><th>时间</th><th>审批意见</th><th></th></tr></thead>
          <tbody><tr v-for="item in recommendations" :key="item.id">
            <td>{{ item.sourceAssociationId }} → {{ item.targetAssociationId }}</td><td>{{ item.matchId ? `匹配 ${item.matchId}` : `需求 ${item.demandId}` }}</td>
            <td>{{ item.summary }}</td><td><StatusBadge :value="displayBusinessStatus(item.status)" /></td><td>{{ formatDateTime(item.createdAt) }}</td><td>{{ item.reviewComment || '—' }}</td>
            <td><div class="inline-actions"><template v-if="canReviewRecommendation(item)"><button class="text-button danger-text" @click="openRecommendationReview(item, 'REJECT')">退回</button><button class="primary-button small" @click="openRecommendationReview(item, 'APPROVE')">确认</button></template><span v-else-if="item.status === 'PENDING_REVIEW' && item.targetAssociationId === currentAssociationId">关系非活动，暂不可审批</span></div></td>
          </tr></tbody>
        </table></div>
        <div v-else class="empty-business-state"><b>暂无跨协会推荐</b></div>
        <PaginationBar :page="recommendationPage" :size="recommendationSize" :total="recommendationTotal" :disabled="loading" @change="changeRecommendationPage" @resize="resizeRecommendationPage" />
      </section>
    </template>

    <div v-if="requestFormOpen" class="modal-backdrop" @click.self="requestFormOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="createRequest">
      <div class="modal-head"><div><span class="eyebrow">ACCESS REQUEST</span><h2>申请接入友好协会</h2></div><button type="button" class="icon-button" @click="requestFormOpen = false">×</button></div>
      <div class="form-grid modal-form"><label class="form-span-2"><span>目标协会 ID *</span><input v-model="requestForm.targetAssociationId" required placeholder="目标协会 UUID" /></label><label class="form-span-2"><span>申请原因</span><textarea v-model="requestForm.reason" rows="4" maxlength="2000" /></label></div>
      <div class="form-actions"><button type="button" class="secondary-button" @click="requestFormOpen = false">取消</button><button class="primary-button" :disabled="busy">提交申请</button></div>
    </form></div>

    <div v-if="accessActionOpen" class="modal-backdrop" @click.self="accessActionOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="submitAccessAction">
      <div class="modal-head"><div><span class="eyebrow">BILATERAL REVIEW</span><h2>{{ accessAction === 'APPROVE' ? '批准接入申请' : accessAction === 'REJECT' ? '驳回接入申请' : '取消接入申请' }}</h2></div><button type="button" class="icon-button" @click="accessActionOpen = false">×</button></div>
      <div class="form-grid modal-form">
        <label v-if="accessAction === 'APPROVE'" class="form-span-2"><span>授权截止时间 *</span><input v-model="accessForm.expiresAt" type="datetime-local" :min="minimumExpiry" required /></label>
        <label v-if="accessAction === 'APPROVE'" class="checkbox-field form-span-2"><input v-model="accessForm.allowMemberData" type="checkbox" /><span>允许在字段策略和企业逐项同意同时满足时共享会员数据</span></label>
        <label class="form-span-2"><span>{{ accessAction === 'APPROVE' ? '审批意见' : '原因 *' }}</span><textarea v-model="accessForm.comment" rows="4" maxlength="2000" :required="accessAction !== 'APPROVE'" /></label>
      </div>
      <div class="form-actions"><button type="button" class="secondary-button" @click="accessActionOpen = false">返回</button><button :class="accessAction === 'APPROVE' ? 'primary-button' : 'secondary-button danger-text'" :disabled="busy">确认</button></div>
    </form></div>

    <div v-if="relationshipActionOpen" class="modal-backdrop" @click.self="relationshipActionOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="submitRelationshipAction">
      <div class="modal-head"><div><span class="eyebrow">RELATIONSHIP</span><h2>{{ relationshipAction === 'ACTIVATE' ? '恢复协会关系' : relationshipAction === 'SUSPEND' ? '暂停协会关系' : '撤销协会关系' }}</h2></div><button type="button" class="icon-button" @click="relationshipActionOpen = false">×</button></div>
      <p v-if="relationshipAction === 'ACTIVATE'">恢复只沿用原授权截止时间；如需延长，须重新发起申请并取得对方批准。</p><p v-if="relationshipAction === 'REVOKE'">撤销后不能单方恢复，双方需要重新走接入审批。</p>
      <div class="form-grid modal-form"><label class="form-span-2"><span>{{ relationshipAction === 'ACTIVATE' ? '恢复说明' : '操作原因 *' }}</span><textarea v-model="relationshipForm.reason" rows="4" maxlength="1000" :required="relationshipAction !== 'ACTIVATE'" /></label></div>
      <div class="form-actions"><button type="button" class="secondary-button" @click="relationshipActionOpen = false">返回</button><button :class="relationshipAction === 'ACTIVATE' ? 'primary-button' : 'secondary-button danger-text'" :disabled="busy">确认</button></div>
    </form></div>

    <div v-if="policyFormOpen" class="modal-backdrop" @click.self="policyFormOpen = false"><form class="panel modal-card" @submit.prevent="savePolicy">
      <div class="modal-head"><div><span class="eyebrow">FIELD POLICY</span><h2>{{ selectedPolicy ? '编辑字段共享策略' : '新建字段共享策略' }}</h2></div><button type="button" class="icon-button" @click="policyFormOpen = false">×</button></div>
      <div class="form-grid modal-form">
        <label><span>目标协会 *</span><select v-model="policyForm.targetAssociationId" required :disabled="Boolean(selectedPolicy)"><option value="" disabled>请选择</option><option v-for="id in activePartnerIds" :key="id" :value="id">{{ id }}</option><option v-if="selectedPolicy && !activePartnerIds.includes(selectedPolicy.targetAssociationId)" :value="selectedPolicy.targetAssociationId">{{ selectedPolicy.targetAssociationId }}</option></select></label>
        <label><span>资源类型 *</span><select v-model="policyForm.resourceType" required :disabled="Boolean(selectedPolicy)" @change="resetPolicyFields"><option v-for="(label, value) in resourceTypeLabels" :key="value" :value="value">{{ label }}</option></select></label>
        <label><span>生效时间 *</span><input v-model="policyForm.validFrom" type="datetime-local" required /></label><label><span>截止时间 *</span><input v-model="policyForm.expiresAt" type="datetime-local" :min="minimumExpiry" required /></label>
        <fieldset class="form-span-2"><legend>允许共享的业务字段 *</legend><div class="segmented"><label v-for="field in currentShareFields" :key="field.value" class="checkbox-field"><input v-model="policyForm.visibleFields" type="checkbox" :value="field.value" :disabled="field.required" /><span>{{ field.label }}{{ field.required ? '（必选）' : '' }}</span></label></div></fieldset>
      </div>
      <p class="form-hint">记录 ID、版本和状态等结构元数据仅用于识别已授权记录；所有业务字段严格按以上白名单裁剪。</p>
      <div class="form-actions"><button type="button" class="secondary-button" @click="policyFormOpen = false">取消</button><button class="primary-button" :disabled="busy">保存策略</button></div>
    </form></div>

    <div v-if="recommendationFormOpen" class="modal-backdrop" @click.self="recommendationFormOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="createRecommendation">
      <div class="modal-head"><div><span class="eyebrow">RECOMMENDATION</span><h2>发起跨协会推荐</h2></div><button type="button" class="icon-button" @click="recommendationFormOpen = false">×</button></div>
      <div class="form-grid modal-form"><label class="form-span-2"><span>目标协会 *</span><select v-model="recommendationForm.targetAssociationId" required><option value="" disabled>请选择</option><option v-for="id in activePartnerIds" :key="id" :value="id">{{ id }}</option></select></label><label><span>真实需求 ID</span><input v-model="recommendationForm.demandId" placeholder="需求 UUID" /></label><label><span>真实匹配 ID</span><input v-model="recommendationForm.matchId" placeholder="匹配 UUID" /></label><label class="form-span-2"><span>推荐说明 *</span><textarea v-model="recommendationForm.summary" rows="4" maxlength="2000" required /></label></div>
      <p class="form-hint">需求 ID 或匹配 ID 至少填写一个；若两者都填，系统会校验二者归属同一需求。</p><div class="form-actions"><button type="button" class="secondary-button" @click="recommendationFormOpen = false">取消</button><button class="primary-button" :disabled="busy">提交推荐</button></div>
    </form></div>

    <div v-if="recommendationReviewOpen" class="modal-backdrop" @click.self="recommendationReviewOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="submitRecommendationReview">
      <div class="modal-head"><div><span class="eyebrow">RECOMMENDATION REVIEW</span><h2>{{ recommendationAction === 'APPROVE' ? '确认跨协会推荐' : '退回跨协会推荐' }}</h2></div><button type="button" class="icon-button" @click="recommendationReviewOpen = false">×</button></div>
      <div class="form-grid modal-form"><label class="form-span-2"><span>审批意见</span><textarea v-model="recommendationComment" rows="4" maxlength="2000" /></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="recommendationReviewOpen = false">取消</button><button :class="recommendationAction === 'APPROVE' ? 'primary-button' : 'secondary-button danger-text'" :disabled="busy">确认</button></div>
    </form></div>
  </div>
</template>
