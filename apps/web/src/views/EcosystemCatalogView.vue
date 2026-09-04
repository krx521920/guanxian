<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type {
  AssociationConsent,
  AssociationConsentTarget,
  AssociationShareResourceType,
  Demand,
  DemandUpsertPayload,
  Offering,
  OfferingUpsertPayload,
  CatalogAction,
} from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText, splitItems } from './business-form'

const auth = useAuth()
const route = useRoute()
const tab = ref<'offerings' | 'demands'>('offerings')
const offerings = ref<Offering[]>([])
const demands = ref<Demand[]>([])
const offeringPage = ref(0)
const demandPage = ref(0)
const pageSize = ref(20)
const offeringTotal = ref(0)
const demandTotal = ref(0)
const loading = ref(false)
const error = ref<PageResourceError | null>(null)
const busy = ref(false)
const message = ref('')
const editorOpen = ref(false)
const editingOffering = ref<Offering | null>(null)
const editingDemand = ref<Demand | null>(null)
const consents = ref<AssociationConsent[]>([])
const consentTargets = ref<AssociationConsentTarget[]>([])
const consentOpen = ref(false)
const consentResource = ref<Offering | Demand | null>(null)
const consentForm = reactive({ targetAssociationId: '', expiresAt: '' })
const keyword = ref('')
const showDeleted = ref(false)
const canOwnWrite = computed(() => auth.user.value?.role === 'ENTERPRISE_ADMIN'
  || (auth.user.value?.role === 'SYSTEM_ADMIN' && Boolean(auth.user.value.enterpriseId)))
const canOperateCatalog = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const canViewDeleted = computed(() => canOwnWrite.value || canOperateCatalog.value)
const canOwn = (item: Offering | Demand) => canOwnWrite.value && auth.user.value?.enterpriseId === item.enterpriseId
const canCatalog = (item: Offering | Demand, action: CatalogAction) => item.allowedActions.includes(action)
const enterpriseLabel = (item: Offering | Demand) => item.enterpriseName?.trim() || item.enterpriseId
const filteredOfferings = computed(() => offerings.value)
const filteredDemands = computed(() => demands.value)
const consentResourceType = computed<AssociationShareResourceType | null>(() => {
  const item = consentResource.value
  if (!item) return null
  return 'kind' in item ? item.kind : 'DEMAND'
})
const consentResourceHistory = computed(() => {
  const item = consentResource.value
  const type = consentResourceType.value
  return item && type
    ? consents.value.filter((consent) => consent.resourceId === item.id && consent.resourceType === type)
    : []
})
const eligibleConsentTargets = computed(() => {
  const type = consentResourceType.value
  if (!type) return []
  return consentTargets.value.filter((target) => target.resourceType === type)
})
const grantableConsentTargets = computed(() => eligibleConsentTargets.value.filter((target) =>
  !consentResourceHistory.value.some((consent) =>
    consent.targetAssociationId === target.targetAssociationId && isConsentActive(consent))))
const minimumConsentExpiry = localDateTime(new Date(Date.now() + 60_000))
const maximumConsentExpiry = computed(() => {
  const target = eligibleConsentTargets.value.find((item) => item.targetAssociationId === consentForm.targetAssociationId)
  return localDateTime(target?.policyExpiresAt)
})
let searchTimer: number | null = null
let loadSequence = 0

const offeringForm = reactive({ name: '', kind: 'PRODUCT' as 'PRODUCT' | 'SERVICE', description: '', scenarios: '', qualifications: '', visibility: 'MEMBERS' })
const demandForm = reactive({ title: '', description: '', scenarios: '', requiredCapabilities: '', visibility: 'MEMBERS', budgetMin: '', budgetMax: '', responseDeadline: '' })

function demandAcceptsResponses(item: Demand): boolean {
  if (item.status !== 'OPEN' || item.visibility === 'DIRECTED') return false
  if (!item.responseDeadline) return true
  const deadline = Date.parse(item.responseDeadline)
  return Number.isFinite(deadline) && deadline > Date.now()
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true; error.value = null
  try {
    const [offeringResult, demandResult] = await Promise.all([
      platformApi.offerings(keyword.value.trim(), showDeleted.value, offeringPage.value, pageSize.value),
      platformApi.demands(keyword.value.trim(), showDeleted.value, demandPage.value, pageSize.value),
    ])
    if (sequence !== loadSequence) return
    offerings.value = offeringResult.items; offeringTotal.value = offeringResult.total; offeringPage.value = offeringResult.page
    demands.value = demandResult.items; demandTotal.value = demandResult.total; demandPage.value = demandResult.page
    let retry = false
    if (!offeringResult.items.length && offeringResult.total > 0 && offeringPage.value > 0) {
      offeringPage.value = Math.max(0, Math.ceil(offeringResult.total / pageSize.value) - 1); retry = true
    }
    if (!demandResult.items.length && demandResult.total > 0 && demandPage.value > 0) {
      demandPage.value = Math.max(0, Math.ceil(demandResult.total / pageSize.value) - 1); retry = true
    }
    if (retry) await load()
  } catch (reason) { if (sequence === loadSequence) error.value = safePageResourceError(reason) }
  finally { if (sequence === loadSequence) loading.value = false }
}

async function loadConsentContext() {
  if (!canOwnWrite.value) return
  try {
    [consents.value, consentTargets.value] = await Promise.all([
      platformApi.associationConsents(),
      platformApi.associationConsentTargets(),
    ])
  } catch (reason) {
    message.value = apiActionMessage(reason, '跨协会授权上下文加载失败，请稍后重试。')
  }
}

function localDateTime(value: string | Date | null | undefined): string {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function consentType(item: Offering | Demand): AssociationShareResourceType {
  return 'kind' in item ? item.kind : 'DEMAND'
}

function isConsentActive(item: AssociationConsent): boolean {
  return item.status === 'ACTIVE' && !item.revokedAt
    && (!item.expiresAt || new Date(item.expiresAt).getTime() > Date.now())
}

function isShareReady(item: Offering | Demand): boolean {
  const approved = 'kind' in item ? item.status === 'ACTIVE' && !item.disabled : item.status === 'OPEN' && !item.disabled
  return canOwn(item) && item.visibility === 'PARTNERS' && approved
    && consentTargets.value.some((target) => target.resourceType === consentType(item))
}

function defaultConsentExpiry(targetAssociationId: string): string {
  const target = eligibleConsentTargets.value.find((item) => item.targetAssociationId === targetAssociationId)
  const oneYear = Date.now() + 365 * 86_400_000
  const policyEnd = target?.policyExpiresAt ? new Date(target.policyExpiresAt).getTime() : Number.POSITIVE_INFINITY
  return localDateTime(new Date(Math.min(oneYear, policyEnd)))
}

function selectConsentTarget() {
  consentForm.expiresAt = defaultConsentExpiry(consentForm.targetAssociationId)
}

function openConsent(item: Offering | Demand) {
  consentResource.value = item
  const first = consentTargets.value.find((target) =>
    target.resourceType === consentType(item)
      && !consents.value.some((consent) => consent.resourceId === item.id
        && consent.resourceType === consentType(item)
        && consent.targetAssociationId === target.targetAssociationId
        && isConsentActive(consent)))
  consentForm.targetAssociationId = first?.targetAssociationId || ''
  consentForm.expiresAt = first ? defaultConsentExpiry(first.targetAssociationId) : ''
  consentOpen.value = true
}

async function grantConsent() {
  const item = consentResource.value
  const type = consentResourceType.value
  const target = grantableConsentTargets.value.find((value) => value.targetAssociationId === consentForm.targetAssociationId)
  if (!item || !type || !target || busy.value) return
  const expiresAt = consentForm.expiresAt ? new Date(consentForm.expiresAt).toISOString() : null
  if (!expiresAt || new Date(expiresAt).getTime() <= Date.now()) {
    message.value = '跨协会授权必须设置未来的截止时间。'
    return
  }
  if (target.policyExpiresAt && new Date(expiresAt).getTime() > new Date(target.policyExpiresAt).getTime()) {
    message.value = '企业授权截止时间不能晚于协会字段策略截止时间。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.grantAssociationConsent({
      enterpriseId: null,
      targetAssociationId: target.targetAssociationId,
      resourceType: type,
      resourceId: item.id,
      expiresAt,
    })
    consents.value = [saved, ...consents.value]
    const next = grantableConsentTargets.value[0]
    consentForm.targetAssociationId = next?.targetAssociationId || ''
    consentForm.expiresAt = next ? defaultConsentExpiry(next.targetAssociationId) : ''
    message.value = '企业逐资源授权已生效；关系、字段策略或本授权任一失效都会立即停止共享。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '跨协会授权失败，请刷新授权目标后重试。')
  } finally {
    busy.value = false
  }
}

async function revokeConsent(item: AssociationConsent) {
  if (busy.value || !isConsentActive(item)) return
  busy.value = true
  message.value = ''
  try {
    const saved = await platformApi.revokeAssociationConsent(item)
    consents.value = consents.value.map((value) => value.id === saved.id ? saved : value)
    if (!consentForm.targetAssociationId) {
      consentForm.targetAssociationId = grantableConsentTargets.value[0]?.targetAssociationId || ''
      selectConsentTarget()
    }
    message.value = '该资源的定向共享授权已撤销。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '撤销授权失败。')
  } finally {
    busy.value = false
  }
}

function openCreate(kind: 'offerings' | 'demands') {
  tab.value = kind; editingOffering.value = null; editingDemand.value = null; message.value = ''
  Object.assign(offeringForm, { name: '', kind: 'PRODUCT', description: '', scenarios: '', qualifications: '', visibility: 'MEMBERS' })
  Object.assign(demandForm, { title: '', description: '', scenarios: '', requiredCapabilities: '', visibility: 'MEMBERS', budgetMin: '', budgetMax: '', responseDeadline: '' })
  editorOpen.value = true
}

function editOffering(item: Offering) {
  editingOffering.value = item; editingDemand.value = null; tab.value = 'offerings'
  Object.assign(offeringForm, { name: item.name, kind: item.kind, description: item.description || '', scenarios: item.scenarios.join('\n'), qualifications: item.qualifications.join('\n'), visibility: item.visibility })
  editorOpen.value = true
}

function editDemand(item: Demand) {
  editingDemand.value = item; editingOffering.value = null; tab.value = 'demands'
  Object.assign(demandForm, { title: item.title, description: item.description, scenarios: item.scenarios.join('\n'), requiredCapabilities: item.requiredCapabilities.join('\n'), visibility: item.visibility, budgetMin: item.budgetMin?.toString() || '', budgetMax: item.budgetMax?.toString() || '', responseDeadline: item.responseDeadline?.slice(0, 16) || '' })
  editorOpen.value = true
}

function offeringPayload(): OfferingUpsertPayload {
  return { name: offeringForm.name.trim(), kind: offeringForm.kind, description: nullableText(offeringForm.description), scenarios: splitItems(offeringForm.scenarios), qualifications: splitItems(offeringForm.qualifications), visibility: offeringForm.visibility }
}

function demandPayload(): DemandUpsertPayload {
  return { title: demandForm.title.trim(), description: demandForm.description.trim(), scenarios: splitItems(demandForm.scenarios), requiredCapabilities: splitItems(demandForm.requiredCapabilities), visibility: demandForm.visibility, budgetMin: demandForm.budgetMin ? Number(demandForm.budgetMin) : null, budgetMax: demandForm.budgetMax ? Number(demandForm.budgetMax) : null, responseDeadline: demandForm.responseDeadline ? new Date(demandForm.responseDeadline).toISOString() : null }
}

async function saveEditor() {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    if (tab.value === 'offerings') {
      await (editingOffering.value ? platformApi.updateOffering(editingOffering.value, offeringPayload()) : platformApi.createOffering(offeringPayload()))
      if (!editingOffering.value) offeringPage.value = 0
    } else {
      await (editingDemand.value ? platformApi.updateDemand(editingDemand.value, demandPayload()) : platformApi.createDemand(demandPayload()))
      if (!editingDemand.value) demandPage.value = 0
    }
    await load(); editorOpen.value = false; message.value = '资料已保存，草稿可继续编辑或提交审核。'
  } catch (reason) { message.value = apiActionMessage(reason, '保存失败，请检查必填项后重试。') }
  finally { busy.value = false }
}

async function actOffering(item: Offering, action: 'submit' | 'disable' | 'enable' | 'restore' | 'delete' | 'approve' | 'reject') {
  if (busy.value) return
  if (action === 'delete' && !window.confirm(`确认删除“${item.name}”吗？删除后可在“显示已删除”中恢复。`)) return
  busy.value = true; message.value = ''
  try {
    if (action === 'delete') await platformApi.deleteOffering(item)
    else if (action === 'restore') await platformApi.restoreOffering(item)
    else if (action === 'enable') await platformApi.enableOffering(item)
    else if (action === 'approve' || action === 'reject') {
      await platformApi.reviewOffering(item, action === 'approve', action === 'reject' ? '请补充资料后重新提交' : '')
    } else await platformApi.transitionOffering(item, action)
    await load()
    message.value = action === 'delete' ? '产品/服务已移入已删除记录。' : action === 'restore' ? '产品/服务已恢复为草稿。' : '产品/服务状态已更新。'
  } catch (reason) { message.value = apiActionMessage(reason, '状态更新失败。') }
  finally { busy.value = false }
}

async function actDemand(item: Demand, action: 'submit' | 'disable' | 'enable' | 'restore' | 'delete' | 'approve' | 'reject' | 'close') {
  if (busy.value) return
  if (action === 'delete' && !window.confirm(`确认删除“${item.title}”吗？删除后可在“显示已删除”中恢复。`)) return
  busy.value = true; message.value = ''
  try {
    if (action === 'delete') await platformApi.deleteDemand(item)
    else if (action === 'restore') await platformApi.restoreDemand(item)
    else if (action === 'enable') await platformApi.enableDemand(item)
    else if (action === 'approve' || action === 'reject') await platformApi.reviewDemand(item, action === 'approve', action === 'reject' ? '请补充需求说明' : '')
    else if (action === 'close') await platformApi.closeDemand(item, '需求方确认关闭')
    else await platformApi.transitionDemand(item, action)
    await load()
    message.value = action === 'delete' ? '需求已移入已删除记录。' : action === 'restore' ? '需求已恢复为草稿。' : '需求状态已更新。'
  } catch (reason) { message.value = apiActionMessage(reason, '状态更新失败。') }
  finally { busy.value = false }
}

function changePage(value: number) { if (tab.value === 'offerings') offeringPage.value = value; else demandPage.value = value; void load() }
function resizePage(value: number) { pageSize.value = value; offeringPage.value = 0; demandPage.value = 0; void load() }
function toggleDeleted() { offeringPage.value = 0; demandPage.value = 0; void load() }

watch(keyword, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { offeringPage.value = 0; demandPage.value = 0; void load() }, 300)
})
onBeforeUnmount(() => { if (searchTimer !== null) window.clearTimeout(searchTimer) })

onMounted(async () => {
  await Promise.all([load(), loadConsentContext()])
  if (!canOwnWrite.value) return
  if (route.query.create === 'demand') openCreate('demands')
  else if (route.query.create === 'offering') openCreate('offerings')
})
</script>

<template>
  <div>
    <PageHeader eyebrow="ECOSYSTEM CATALOG" title="产业生态资产" description="产品、服务与需求独立建档，经审核后进入生态匹配">
      <button v-if="canOwnWrite" class="secondary-button" type="button" @click="openCreate('offerings')">+ 新建产品/服务</button>
      <button v-if="canOwnWrite" class="primary-button" type="button" @click="openCreate('demands')">+ 发布需求</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="panel filter-panel">
      <div class="segmented"><button :class="{ active: tab === 'offerings' }" @click="tab = 'offerings'">产品与服务（{{ offeringTotal }}）</button><button :class="{ active: tab === 'demands' }" @click="tab = 'demands'">合作需求（{{ demandTotal }}）</button></div>
      <div class="search-box compact"><span>⌕</span><input v-model="keyword" :placeholder="tab === 'offerings' ? '搜索产品、企业或场景' : '搜索需求、企业或场景'" /></div>
      <label v-if="canViewDeleted" class="checkbox-row"><input v-model="showDeleted" type="checkbox" @change="toggleDeleted" /> 显示已删除</label>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="tab === 'offerings'" class="asset-grid">
      <article v-for="item in filteredOfferings" :key="item.id" class="panel asset-card">
        <div class="asset-card-head"><span class="eyebrow">{{ item.kind === 'PRODUCT' ? '产品' : '服务' }} · {{ enterpriseLabel(item) }}</span><StatusBadge :value="item.deleted ? '已删除' : displayBusinessStatus(item.status)" /></div>
        <h2>{{ item.name }}</h2><p>{{ item.description || '暂无详细说明' }}</p>
        <div class="tags"><span v-for="scene in item.scenarios" :key="scene">{{ scene }}</span></div>
        <small>更新于 {{ formatDateTime(item.updatedAt) }} · {{ item.visibility }}<template v-if="item.deletedAt"> · 删除于 {{ formatDateTime(item.deletedAt) }}</template></small>
        <div class="card-actions"><button v-if="canCatalog(item, 'UPDATE')" class="text-button" @click="editOffering(item)">编辑</button><button v-if="canCatalog(item, 'SUBMIT')" class="secondary-button small" :disabled="busy" @click="actOffering(item, 'submit')">提交审核</button><button v-if="canCatalog(item, 'REVIEW')" class="secondary-button small" @click="actOffering(item, 'reject')">退回</button><button v-if="canCatalog(item, 'REVIEW')" class="primary-button small" @click="actOffering(item, 'approve')">通过</button><button v-if="!item.deleted && isShareReady(item)" class="secondary-button small" @click="openConsent(item)">跨协会授权</button><button v-if="canCatalog(item, 'DISABLE')" class="text-button danger-text" :disabled="busy" @click="actOffering(item, 'disable')">停用</button><button v-if="canCatalog(item, 'ENABLE')" class="text-button" :disabled="busy" @click="actOffering(item, 'enable')">恢复编辑</button><button v-if="canCatalog(item, 'DELETE')" class="text-button danger-text" :disabled="busy" @click="actOffering(item, 'delete')">删除</button><button v-if="canCatalog(item, 'RESTORE')" class="text-button" :disabled="busy" @click="actOffering(item, 'restore')">恢复为草稿</button></div>
      </article>
      <div v-if="!filteredOfferings.length" class="panel empty-business-state"><b>暂无产品或服务</b><span>建档后才能被需求匹配和协会推荐。</span></div>
      <PaginationBar :page="offeringPage" :size="pageSize" :total="offeringTotal" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <section v-else class="asset-grid">
      <article v-for="item in filteredDemands" :key="item.id" class="panel asset-card">
        <div class="asset-card-head"><span class="eyebrow">需求方 · {{ enterpriseLabel(item) }}</span><StatusBadge :value="item.deleted ? '已删除' : displayBusinessStatus(item.status)" /></div>
        <h2>{{ item.title }}</h2><p>{{ item.description }}</p><div class="tags"><span v-for="scene in item.scenarios" :key="scene">{{ scene }}</span></div>
        <small>预算 {{ item.budgetMin ?? '—' }} ~ {{ item.budgetMax ?? '—' }} · 截止 {{ formatDateTime(item.responseDeadline) }}<template v-if="item.deletedAt"> · 删除于 {{ formatDateTime(item.deletedAt) }}</template></small>
        <div class="card-actions"><button v-if="canCatalog(item, 'UPDATE')" class="text-button" @click="editDemand(item)">编辑</button><button v-if="canCatalog(item, 'SUBMIT')" class="secondary-button small" @click="actDemand(item, 'submit')">提交审核</button><button v-if="canCatalog(item, 'REVIEW')" class="secondary-button small" @click="actDemand(item, 'reject')">退回</button><button v-if="canCatalog(item, 'REVIEW')" class="primary-button small" @click="actDemand(item, 'approve')">通过</button><RouterLink v-if="!item.deleted && demandAcceptsResponses(item)" class="secondary-button small" :to="`/matching?demand=${item.id}`">查看匹配</RouterLink><span v-else-if="!item.deleted && item.status === 'OPEN'" class="form-hint">响应已截止</span><button v-if="!item.deleted && isShareReady(item)" class="secondary-button small" @click="openConsent(item)">跨协会授权</button><button v-if="canCatalog(item, 'CLOSE')" class="text-button danger-text" :disabled="busy" @click="actDemand(item, 'close')">关闭需求</button><button v-if="canCatalog(item, 'DISABLE')" class="text-button danger-text" :disabled="busy" @click="actDemand(item, 'disable')">停用</button><button v-if="canCatalog(item, 'ENABLE')" class="text-button" :disabled="busy" @click="actDemand(item, 'enable')">恢复编辑</button><button v-if="canCatalog(item, 'DELETE')" class="text-button danger-text" :disabled="busy" @click="actDemand(item, 'delete')">删除</button><button v-if="canCatalog(item, 'RESTORE')" class="text-button" :disabled="busy" @click="actDemand(item, 'restore')">恢复为草稿</button></div>
      </article>
      <div v-if="!filteredDemands.length" class="panel empty-business-state"><b>暂无合作需求</b><span>发布真实需求后，系统将基于在架能力生成匹配。</span></div>
      <PaginationBar :page="demandPage" :size="pageSize" :total="demandTotal" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="editorOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="editorOpen = false">
      <form class="panel modal-card" @submit.prevent="saveEditor">
        <div class="modal-head"><div><span class="eyebrow">{{ tab === 'offerings' ? 'OFFERING' : 'DEMAND' }}</span><h2>{{ (editingOffering || editingDemand) ? '编辑资料' : '新建资料' }}</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="editorOpen = false">×</button></div>
        <div v-if="tab === 'offerings'" class="form-grid modal-form"><label><span>名称 *</span><input v-model="offeringForm.name" required maxlength="200" /></label><label><span>类型</span><select v-model="offeringForm.kind"><option value="PRODUCT">产品</option><option value="SERVICE">服务</option></select></label><label class="form-span-2"><span>详细说明</span><textarea v-model="offeringForm.description" rows="5" /></label><label><span>适用场景</span><textarea v-model="offeringForm.scenarios" rows="4" /></label><label><span>资质与证书</span><textarea v-model="offeringForm.qualifications" rows="4" /></label><label><span>可见范围</span><select v-model="offeringForm.visibility"><option value="PRIVATE">仅本企业与协会</option><option value="MEMBERS">本协会会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div>
        <div v-else class="form-grid modal-form"><label class="form-span-2"><span>需求标题 *</span><input v-model="demandForm.title" required maxlength="200" /></label><label class="form-span-2"><span>需求说明 *</span><textarea v-model="demandForm.description" required rows="5" /></label><label><span>应用场景</span><textarea v-model="demandForm.scenarios" rows="4" /></label><label><span>所需能力</span><textarea v-model="demandForm.requiredCapabilities" rows="4" /></label><label><span>预算下限</span><input v-model="demandForm.budgetMin" type="number" min="0" step="0.01" /></label><label><span>预算上限</span><input v-model="demandForm.budgetMax" type="number" min="0" step="0.01" /></label><label><span>响应截止</span><input v-model="demandForm.responseDeadline" type="datetime-local" /></label><label><span>可见范围</span><select v-model="demandForm.visibility"><option value="PRIVATE">仅本企业与协会</option><option value="MEMBERS">本协会会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div>
        <div class="form-actions"><button type="button" class="secondary-button" @click="editorOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存草稿' }}</button></div>
      </form>
    </div>

    <div v-if="consentOpen && consentResource" class="modal-backdrop" @click.self="consentOpen = false">
      <div class="panel modal-card compact-modal">
        <div class="modal-head">
          <div><span class="eyebrow">RESOURCE CONSENT</span><h2>跨协会逐资源授权</h2></div>
          <button type="button" class="icon-button" aria-label="关闭" @click="consentOpen = false">×</button>
        </div>
        <div class="form-section">
          <h3>{{ 'kind' in consentResource ? consentResource.name : consentResource.title }}</h3>
          <p>只有协会关系、字段策略、资源可见范围和本企业授权全部有效时，目标协会才能看到已批准字段。</p>
        </div>
        <form v-if="grantableConsentTargets.length" class="form-grid modal-form" @submit.prevent="grantConsent">
          <label class="form-span-2"><span>目标协会 *</span><select v-model="consentForm.targetAssociationId" required @change="selectConsentTarget"><option value="" disabled>请选择</option><option v-for="target in grantableConsentTargets" :key="target.targetAssociationId" :value="target.targetAssociationId">{{ target.targetAssociationId }}</option></select></label>
          <label class="form-span-2"><span>授权截止时间 *</span><input v-model="consentForm.expiresAt" type="datetime-local" :min="minimumConsentExpiry" :max="maximumConsentExpiry || undefined" required /></label>
          <div class="form-actions form-span-2"><button class="primary-button" :disabled="busy">确认授权</button></div>
        </form>
        <div v-else class="empty-business-state"><b>没有新的可授权目标</b><span>当前有效字段策略对应的友好协会均已授权，或暂无可用策略。</span></div>
        <div class="panel-header"><div><h2>授权记录</h2><p>撤销立即生效；重新授权会生成新的审计记录。</p></div></div>
        <div v-if="consentResourceHistory.length" class="data-table-wrap"><table class="data-table">
          <thead><tr><th>目标协会</th><th>状态</th><th>授权截止</th><th>授权/撤销时间</th><th></th></tr></thead>
          <tbody><tr v-for="item in consentResourceHistory" :key="item.id">
            <td>{{ item.targetAssociationId }}</td><td><StatusBadge :value="displayBusinessStatus(isConsentActive(item) ? 'ACTIVE' : item.revokedAt ? 'REVOKED' : 'EXPIRED')" /></td><td>{{ formatDateTime(item.expiresAt) }}</td>
            <td>{{ item.revokedAt ? formatDateTime(item.revokedAt) : formatDateTime(item.createdAt) }}</td><td><button v-if="isConsentActive(item)" class="text-button danger-text" :disabled="busy" @click="revokeConsent(item)">撤销</button></td>
          </tr></tbody>
        </table></div>
        <div v-else class="empty-business-state"><b>暂无授权记录</b></div>
      </div>
    </div>
  </div>
</template>
