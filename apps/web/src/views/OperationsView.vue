<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { createLatestRequestGate } from '../services/latest-request'
import { platformApi } from '../services/platform-api'
import type { AccessBinding, AccessBindingPayload, AuditRecord, SystemEnterpriseOption } from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText } from './business-form'

const auth = useAuth()
const activeTab = ref<'audit' | 'bindings'>('audit')
const auditItems = ref<AuditRecord[]>([])
const auditPage = ref(0)
const auditSize = ref(20)
const auditTotal = ref(0)
const auditSnapshotId = ref<number | null>(null)
const bindings = ref<AccessBinding[]>([])
const enterprises = ref<SystemEnterpriseOption[]>([])
const auditLoading = ref(false)
const bindingLoading = ref(false)
const auditError = ref<PageResourceError | null>(null)
const bindingError = ref<PageResourceError | null>(null)
const busy = ref(false)
const message = ref('')
const editorOpen = ref(false)
const editing = ref<AccessBinding | null>(null)
const selectedAudit = ref<AuditRecord | null>(null)
const form = reactive({ externalSubject: '', username: '', displayName: '', email: '', enterpriseId: '' })
const auditRequestGate = createLatestRequestGate()
const bindingRequestGate = createLatestRequestGate()

const isSystemAdmin = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN')
const canUseBindings = computed(() => isSystemAdmin.value && !auth.isDemoMode)
const currentAssociationId = computed(() => auth.user.value?.associationId || null)
const currentEnterpriseId = computed(() => auth.user.value?.enterpriseId || null)
const canWriteBindings = computed(() => canUseBindings.value && Boolean(currentAssociationId.value))

async function loadAudit() {
  const requestEpoch = auditRequestGate.begin()
  auditLoading.value = true
  auditError.value = null
  auditItems.value = []
  auditTotal.value = 0
  try {
    let result = await platformApi.auditLogPage(
      currentEnterpriseId.value || '', auditPage.value, auditSize.value, auditSnapshotId.value,
    )
    if (!auditRequestGate.isCurrent(requestEpoch)) return
    if (!result.items.length && result.total > 0 && result.page > 0) {
      auditPage.value = Math.max(0, Math.ceil(result.total / result.size) - 1)
      result = await platformApi.auditLogPage(
        currentEnterpriseId.value || '', auditPage.value, auditSize.value, result.snapshotId,
      )
    }
    if (!auditRequestGate.isCurrent(requestEpoch)) return
    auditItems.value = result.items
    auditPage.value = result.page
    auditSize.value = result.size
    auditTotal.value = result.total
    auditSnapshotId.value = result.snapshotId
  } catch (reason) {
    if (auditRequestGate.isCurrent(requestEpoch)) auditError.value = safePageResourceError(reason)
  } finally {
    if (auditRequestGate.isCurrent(requestEpoch)) auditLoading.value = false
  }
}

async function loadBindings() {
  const requestEpoch = bindingRequestGate.begin()
  if (!canUseBindings.value) {
    bindings.value = []
    enterprises.value = []
    bindingError.value = null
    bindingLoading.value = false
    return
  }
  bindingLoading.value = true
  bindingError.value = null
  try {
    const [values, enterpriseValues] = await Promise.all([
      platformApi.accessBindings(),
      currentAssociationId.value
        ? platformApi.systemEnterprises(currentAssociationId.value)
        : Promise.resolve([]),
    ])
    if (!bindingRequestGate.isCurrent(requestEpoch)) return
    bindings.value = values
    enterprises.value = enterpriseValues
  } catch (reason) {
    if (bindingRequestGate.isCurrent(requestEpoch)) bindingError.value = safePageResourceError(reason)
  } finally {
    if (bindingRequestGate.isCurrent(requestEpoch)) bindingLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadAudit(), canUseBindings.value ? loadBindings() : Promise.resolve()])
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    externalSubject: '', username: '', displayName: '', email: '',
    enterpriseId: currentEnterpriseId.value || '',
  })
  editorOpen.value = true
}

function openEdit(item: AccessBinding) {
  editing.value = item
  Object.assign(form, {
    externalSubject: item.externalSubject || '',
    username: item.username,
    displayName: item.displayName,
    email: item.email || '',
    enterpriseId: item.enterpriseId || '',
  })
  editorOpen.value = true
}

function payload(): AccessBindingPayload {
  return {
    externalSubject: form.externalSubject.trim(),
    username: form.username.trim(),
    displayName: form.displayName.trim(),
    email: nullableText(form.email),
    associationId: currentAssociationId.value,
    enterpriseId: form.enterpriseId || null,
  }
}

async function saveBinding() {
  if (!canWriteBindings.value || busy.value) return
  busy.value = true
  message.value = ''
  try {
    await platformApi.saveAccessBinding(payload(), editing.value?.version)
    editorOpen.value = false
    message.value = editing.value ? '账号绑定已更新并写入审计日志。' : '账号绑定已建立并写入审计日志。'
    editing.value = null
    auditSnapshotId.value = null
    auditPage.value = 0
    await Promise.all([loadBindings(), loadAudit()])
  } catch (reason) {
    message.value = apiActionMessage(reason, '账号绑定保存失败，请重新加载后再试。')
  } finally {
    busy.value = false
  }
}

async function changeBinding(item: AccessBinding, action: 'disable' | 'restore' | 'unbind') {
  if (busy.value || item.externalSubject === auth.user.value?.id) return
  if (action === 'unbind' && !window.confirm(`确认解除“${item.displayName}”的外部身份绑定？解除后该身份将无法登录。`)) return
  busy.value = true
  message.value = ''
  try {
    if (action === 'disable') await platformApi.disableAccessBinding(item)
    else if (action === 'restore') await platformApi.restoreAccessBinding(item)
    else await platformApi.unbindAccessBinding(item)
    message.value = action === 'disable' ? '账号已停用。' : action === 'restore' ? '账号已恢复。' : '外部身份绑定已解除。'
    auditSnapshotId.value = null
    auditPage.value = 0
    await Promise.all([loadBindings(), loadAudit()])
  } catch (reason) {
    message.value = apiActionMessage(reason, '账号状态更新失败，请重新加载后再试。')
  } finally {
    busy.value = false
  }
}

function details(value: Record<string, unknown>): string {
  const entries = Object.entries(value || {})
  if (!entries.length) return '—'
  return entries.slice(0, 3).map(([key, item]) => {
    const display = typeof item === 'object' && item !== null ? JSON.stringify(item) : String(item)
    return `${key}: ${display}`
  }).join('；')
}

function refreshAudit() { selectedAudit.value = null; auditSnapshotId.value = null; auditPage.value = 0; void loadAudit() }
function changeAuditPage(value: number) { auditPage.value = value; void loadAudit() }
function resizeAuditPage(value: number) { auditSize.value = value; auditPage.value = 0; void loadAudit() }

watch(
  () => `${currentAssociationId.value || ''}:${currentEnterpriseId.value || ''}`,
  () => {
    auditRequestGate.invalidate()
    bindingRequestGate.invalidate()
    editorOpen.value = false
    auditItems.value = []
    selectedAudit.value = null
    bindings.value = []
    enterprises.value = []
    auditPage.value = 0
    auditSnapshotId.value = null
    void loadAll()
  },
)

onMounted(loadAll)
</script>

<template>
  <div>
    <PageHeader eyebrow="OPERATIONS" title="审计与账号" description="核对业务操作留痕；系统管理员可在选定协会上下文内维护 OIDC 身份绑定">
      <button v-if="canWriteBindings" class="primary-button" type="button" @click="openCreate">+ 绑定账号</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <div class="segmented page-tabs">
      <button type="button" :class="{ active: activeTab === 'audit' }" @click="activeTab = 'audit'">操作审计</button>
      <button v-if="canUseBindings" type="button" :class="{ active: activeTab === 'bindings' }" @click="activeTab = 'bindings'">账号绑定</button>
    </div>

    <section v-if="activeTab === 'audit'" class="panel business-section">
      <div class="panel-header"><div><h2>操作记录</h2><p>按当前数据范围分页检索真实审计记录，包含请求编号便于日志追踪。</p></div><button class="text-button" type="button" :disabled="auditLoading" @click="refreshAudit">刷新</button></div>
      <AsyncResourceState v-if="auditLoading || auditError" :loading="auditLoading" :error="auditError" @retry="loadAudit" />
      <div v-else-if="auditItems.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>资源</th><th>结果</th><th>详情摘要</th><th>请求编号</th></tr></thead><tbody><tr v-for="item in auditItems" :key="item.id"><td>{{ formatDateTime(item.occurredAt) }}</td><td>{{ item.actorUsername || item.actorSubject }}</td><td>{{ displayBusinessStatus(item.action) }}</td><td>{{ item.resourceType }}<small class="table-subline">{{ item.resourceId }} · v{{ item.resourceVersion ?? '—' }}</small></td><td><StatusBadge :value="displayBusinessStatus(item.outcome)" /></td><td>{{ details(item.details) }} <button class="text-button" type="button" @click="selectedAudit = item">查看完整详情</button></td><td>{{ item.requestId || '—' }}</td></tr></tbody></table></div>
      <div v-else class="empty-business-state"><b>当前范围暂无审计记录</b><span>新增、修改、审核、停用、删除和恢复操作会在成功后写入这里。</span></div>
      <PaginationBar v-if="!auditError" :page="auditPage" :size="auditSize" :total="auditTotal" :disabled="auditLoading" @change="changeAuditPage" @resize="resizeAuditPage" />
    </section>

    <div v-if="selectedAudit" class="modal-backdrop" @click.self="selectedAudit = null"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">AUDIT EVIDENCE</span><h2>审计完整详情</h2></div><button class="icon-button" type="button" @click="selectedAudit = null">×</button></div><div class="modal-copy"><p><strong>{{ displayBusinessStatus(selectedAudit.action) }}</strong> · {{ selectedAudit.resourceType }} / {{ selectedAudit.resourceId }}</p><p>操作者：{{ selectedAudit.actorUsername || selectedAudit.actorSubject }} · {{ formatDateTime(selectedAudit.occurredAt) }}</p><p>请求编号：{{ selectedAudit.requestId || '—' }} · 资源版本：{{ selectedAudit.resourceVersion ?? '—' }}</p><pre>{{ JSON.stringify(selectedAudit.details, null, 2) }}</pre></div></section></div>

    <section v-if="activeTab === 'bindings'" class="panel business-section">
      <div class="panel-header"><div><h2>OIDC 账号绑定</h2><p>角色由正式身份提供方签发；此处只绑定外部身份与协会、企业数据范围。</p></div><button class="text-button" type="button" :disabled="bindingLoading" @click="loadBindings">刷新</button></div>
      <div v-if="!currentAssociationId" class="notice-banner warning">请先在页面顶部选择协会上下文，再新增或修改账号绑定；全平台上下文仅可查看。</div>
      <AsyncResourceState v-if="bindingLoading || bindingError" :loading="bindingLoading" :error="bindingError" @retry="loadBindings" />
      <div v-else-if="bindings.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>外部身份</th><th>数据范围</th><th>状态</th><th>版本</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="item in bindings" :key="item.id"><td><strong>{{ item.displayName }}</strong><small class="table-subline">{{ item.username }} · {{ item.email || '未登记邮箱' }}</small></td><td>{{ item.externalSubject || '已解绑' }}</td><td>{{ item.associationName || item.associationId || '—' }}<small class="table-subline">{{ item.enterpriseName || item.enterpriseId || '协会级账号' }}</small></td><td><StatusBadge :value="displayBusinessStatus(item.status)" /></td><td>{{ item.version }}</td><td>{{ formatDateTime(item.updatedAt) }}</td><td><div v-if="item.externalSubject !== auth.user.value?.id" class="inline-actions"><button v-if="canWriteBindings" class="text-button" type="button" :disabled="busy" @click="openEdit(item)">{{ item.bound ? '编辑' : '重新绑定' }}</button><button v-if="canWriteBindings && item.status === 'ACTIVE'" class="text-button danger-text" type="button" :disabled="busy" @click="changeBinding(item, 'disable')">停用</button><button v-if="canWriteBindings && item.status !== 'ACTIVE' && item.bound" class="text-button" type="button" :disabled="busy" @click="changeBinding(item, 'restore')">恢复</button><button v-if="canWriteBindings && item.bound" class="text-button danger-text" type="button" :disabled="busy" @click="changeBinding(item, 'unbind')">解绑</button></div><span v-else>当前账号不可自改</span></td></tr></tbody></table></div>
      <div v-else class="empty-business-state"><b>当前范围暂无账号绑定</b><span>正式 JWT 模式下，可在选定协会上下文内建立绑定。</span></div>
    </section>

    <div v-if="editorOpen" class="modal-backdrop" @click.self="editorOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="saveBinding"><div class="modal-head"><div><span class="eyebrow">IDENTITY BINDING</span><h2>{{ editing ? '编辑账号绑定' : '绑定 OIDC 账号' }}</h2></div><button class="icon-button" type="button" @click="editorOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>外部身份 Subject *</span><input v-model="form.externalSubject" required maxlength="200" /></label><label><span>登录名 *</span><input v-model="form.username" required maxlength="100" /></label><label><span>显示名称 *</span><input v-model="form.displayName" required maxlength="100" /></label><label><span>邮箱</span><input v-model="form.email" type="email" maxlength="254" /></label><label><span>企业范围</span><select v-model="form.enterpriseId" :disabled="Boolean(currentEnterpriseId)"><option value="">协会级账号</option><option v-for="item in enterprises" :key="item.id" :value="item.id">{{ item.name }}</option></select></label></div><p class="form-hint">当前协会：{{ auth.user.value?.organization }}。企业管理员或企业成员应选择具体企业；协会角色应保留“协会级账号”。</p><div class="form-actions"><button class="secondary-button" type="button" @click="editorOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : '保存绑定' }}</button></div></form></div>
  </div>
</template>
