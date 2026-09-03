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
import type { Demand, DemandUpsertPayload, Offering, OfferingUpsertPayload } from '../types/domain'
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
const keyword = ref('')
const canOwnWrite = computed(() => auth.user.value?.role === 'ENTERPRISE_ADMIN'
  || (auth.user.value?.role === 'SYSTEM_ADMIN' && Boolean(auth.user.value.enterpriseId)))
const canModerate = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const canOperateCatalog = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const canOwn = (item: Offering | Demand) => canOwnWrite.value && auth.user.value?.enterpriseId === item.enterpriseId
const enterpriseLabel = (item: Offering | Demand) => item.enterpriseName?.trim() || item.enterpriseId
const filteredOfferings = computed(() => offerings.value)
const filteredDemands = computed(() => demands.value)
let searchTimer: number | null = null

const offeringForm = reactive({ name: '', kind: 'PRODUCT' as 'PRODUCT' | 'SERVICE', description: '', scenarios: '', qualifications: '', visibility: 'MEMBERS' })
const demandForm = reactive({ title: '', description: '', scenarios: '', requiredCapabilities: '', visibility: 'MEMBERS', budgetMin: '', budgetMax: '', responseDeadline: '' })

async function load() {
  if (loading.value) return
  loading.value = true; error.value = null
  try {
    const [offeringResult, demandResult] = await Promise.all([
      platformApi.offerings(keyword.value.trim(), false, offeringPage.value, pageSize.value),
      platformApi.demands(keyword.value.trim(), false, demandPage.value, pageSize.value),
    ])
    offerings.value = offeringResult.items; offeringTotal.value = offeringResult.total; offeringPage.value = offeringResult.page
    demands.value = demandResult.items; demandTotal.value = demandResult.total; demandPage.value = demandResult.page
  } catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
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

async function actOffering(item: Offering, action: 'submit' | 'disable' | 'restore' | 'approve' | 'reject') {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = action === 'approve' || action === 'reject'
      ? await platformApi.reviewOffering(item, action === 'approve', action === 'reject' ? '请补充资料后重新提交' : '')
      : await platformApi.transitionOffering(item, action)
    offerings.value = offerings.value.map((value) => value.id === saved.id ? saved : value)
    message.value = '产品/服务状态已更新。'
  } catch (reason) { message.value = apiActionMessage(reason, '状态更新失败。') }
  finally { busy.value = false }
}

async function actDemand(item: Demand, action: 'submit' | 'disable' | 'restore' | 'approve' | 'reject' | 'close') {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    let saved: Demand
    if (action === 'approve' || action === 'reject') saved = await platformApi.reviewDemand(item, action === 'approve', action === 'reject' ? '请补充需求说明' : '')
    else if (action === 'close') saved = await platformApi.closeDemand(item, '需求方确认关闭')
    else saved = await platformApi.transitionDemand(item, action)
    demands.value = demands.value.map((value) => value.id === saved.id ? saved : value)
    message.value = '需求状态已更新。'
  } catch (reason) { message.value = apiActionMessage(reason, '状态更新失败。') }
  finally { busy.value = false }
}

function changePage(value: number) { if (tab.value === 'offerings') offeringPage.value = value; else demandPage.value = value; void load() }
function resizePage(value: number) { pageSize.value = value; offeringPage.value = 0; demandPage.value = 0; void load() }

watch(keyword, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { offeringPage.value = 0; demandPage.value = 0; void load() }, 300)
})
onBeforeUnmount(() => { if (searchTimer !== null) window.clearTimeout(searchTimer) })

onMounted(async () => {
  await load()
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
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="tab === 'offerings'" class="asset-grid">
      <article v-for="item in filteredOfferings" :key="item.id" class="panel asset-card">
        <div class="asset-card-head"><span class="eyebrow">{{ item.kind === 'PRODUCT' ? '产品' : '服务' }} · {{ enterpriseLabel(item) }}</span><StatusBadge :value="displayBusinessStatus(item.status)" /></div>
        <h2>{{ item.name }}</h2><p>{{ item.description || '暂无详细说明' }}</p>
        <div class="tags"><span v-for="scene in item.scenarios" :key="scene">{{ scene }}</span></div>
        <small>更新于 {{ formatDateTime(item.updatedAt) }} · {{ item.visibility }}</small>
        <div class="card-actions"><button v-if="canOwn(item)" class="text-button" @click="editOffering(item)">编辑</button><button v-if="canOwn(item) && item.status === 'DRAFT'" class="secondary-button small" :disabled="busy" @click="actOffering(item, 'submit')">提交审核</button><button v-if="canModerate && item.status === 'PENDING_REVIEW'" class="secondary-button small" @click="actOffering(item, 'reject')">退回</button><button v-if="canModerate && item.status === 'PENDING_REVIEW'" class="primary-button small" @click="actOffering(item, 'approve')">通过</button><button v-if="(canOwn(item) || canOperateCatalog) && !item.disabled" class="text-button danger-text" @click="actOffering(item, 'disable')">停用</button><button v-if="(canOwn(item) || canOperateCatalog) && item.disabled" class="text-button" @click="actOffering(item, 'restore')">恢复</button></div>
      </article>
      <div v-if="!filteredOfferings.length" class="panel empty-business-state"><b>暂无产品或服务</b><span>建档后才能被需求匹配和协会推荐。</span></div>
      <PaginationBar :page="offeringPage" :size="pageSize" :total="offeringTotal" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <section v-else class="asset-grid">
      <article v-for="item in filteredDemands" :key="item.id" class="panel asset-card">
        <div class="asset-card-head"><span class="eyebrow">需求方 · {{ enterpriseLabel(item) }}</span><StatusBadge :value="displayBusinessStatus(item.status)" /></div>
        <h2>{{ item.title }}</h2><p>{{ item.description }}</p><div class="tags"><span v-for="scene in item.scenarios" :key="scene">{{ scene }}</span></div>
        <small>预算 {{ item.budgetMin ?? '—' }} ~ {{ item.budgetMax ?? '—' }} · 截止 {{ formatDateTime(item.responseDeadline) }}</small>
        <div class="card-actions"><button v-if="canOwn(item)" class="text-button" @click="editDemand(item)">编辑</button><button v-if="canOwn(item) && item.status === 'DRAFT'" class="secondary-button small" @click="actDemand(item, 'submit')">提交审核</button><button v-if="canModerate && item.status === 'PENDING_REVIEW'" class="secondary-button small" @click="actDemand(item, 'reject')">退回</button><button v-if="canModerate && item.status === 'PENDING_REVIEW'" class="primary-button small" @click="actDemand(item, 'approve')">通过</button><RouterLink v-if="item.status === 'OPEN'" class="secondary-button small" :to="`/matching?demand=${item.id}`">查看匹配</RouterLink><button v-if="(canOwn(item) || canOperateCatalog) && !['CLOSED', 'DISABLED'].includes(item.status)" class="text-button danger-text" @click="actDemand(item, 'close')">关闭需求</button></div>
      </article>
      <div v-if="!filteredDemands.length" class="panel empty-business-state"><b>暂无合作需求</b><span>发布真实需求后，系统将基于在架能力生成匹配。</span></div>
      <PaginationBar :page="demandPage" :size="pageSize" :total="demandTotal" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="editorOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="editorOpen = false">
      <form class="panel modal-card" @submit.prevent="saveEditor">
        <div class="modal-head"><div><span class="eyebrow">{{ tab === 'offerings' ? 'OFFERING' : 'DEMAND' }}</span><h2>{{ (editingOffering || editingDemand) ? '编辑资料' : '新建资料' }}</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="editorOpen = false">×</button></div>
        <div v-if="tab === 'offerings'" class="form-grid modal-form"><label><span>名称 *</span><input v-model="offeringForm.name" required maxlength="200" /></label><label><span>类型</span><select v-model="offeringForm.kind"><option value="PRODUCT">产品</option><option value="SERVICE">服务</option></select></label><label class="form-span-2"><span>详细说明</span><textarea v-model="offeringForm.description" rows="5" /></label><label><span>适用场景</span><textarea v-model="offeringForm.scenarios" rows="4" /></label><label><span>资质与证书</span><textarea v-model="offeringForm.qualifications" rows="4" /></label><label><span>可见范围</span><select v-model="offeringForm.visibility"><option value="PRIVATE">仅本企业与协会</option><option value="MEMBERS">本协会会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option></select></label></div>
        <div v-else class="form-grid modal-form"><label class="form-span-2"><span>需求标题 *</span><input v-model="demandForm.title" required maxlength="200" /></label><label class="form-span-2"><span>需求说明 *</span><textarea v-model="demandForm.description" required rows="5" /></label><label><span>应用场景</span><textarea v-model="demandForm.scenarios" rows="4" /></label><label><span>所需能力</span><textarea v-model="demandForm.requiredCapabilities" rows="4" /></label><label><span>预算下限</span><input v-model="demandForm.budgetMin" type="number" min="0" step="0.01" /></label><label><span>预算上限</span><input v-model="demandForm.budgetMax" type="number" min="0" step="0.01" /></label><label><span>响应截止</span><input v-model="demandForm.responseDeadline" type="datetime-local" /></label><label><span>可见范围</span><select v-model="demandForm.visibility"><option value="PRIVATE">仅本企业与协会</option><option value="MEMBERS">本协会会员</option><option value="PARTNERS">友好协会</option><option value="PUBLIC">公开</option><option value="DIRECTED">定向</option></select></label></div>
        <div class="form-actions"><button type="button" class="secondary-button" @click="editorOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存草稿' }}</button></div>
      </form>
    </div>
  </div>
</template>
