<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import ProfileReviewQueue from '../components/ProfileReviewQueue.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { createLatestRequestGate } from '../services/latest-request'
import { platformApi } from '../services/platform-api'
import type { MemberImportPreview, MemberProfile } from '../types/domain'
import { hasAssociationWriteContext, normalizeMemberDeletedStatus } from './business-view-guards'
import { formatDateTime } from './business-form'

const auth = useAuth()
const route = useRoute()
const items = ref<Awaited<ReturnType<typeof platformApi.members>>['items']>([])
const loading = ref(false)
const error = ref<PageResourceError | null>(null)
const page = ref(0)
const size = ref(20)
const total = ref(0)
const keyword = ref('')
const status = ref('全部')
const includeDeleted = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const importButton = ref<HTMLButtonElement | null>(null)
const importGuide = ref(false)
const importPreview = ref<MemberImportPreview | null>(null)
const importBusy = ref(false)
const importMessage = ref<string | null>(null)
const viewing = ref<MemberProfile | null>(null)
const viewBusy = ref(false)
const loadedAt = ref<Date | null>(null)
const sortKey = ref<'' | 'completeness' | 'updatedAt'>('')
const sortDir = ref<'asc' | 'desc'>('desc')
const selectedIds = ref<Set<string>>(new Set())
const bulkBusy = ref(false)
const role = computed(() => auth.user.value?.role || '')
const hasAssociationContext = computed(() => hasAssociationWriteContext(role.value, auth.user.value?.associationId))
const canCollectRole = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(role.value))
const canCollect = computed(() => canCollectRole.value && hasAssociationContext.value)
const canSeeDeleted = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(role.value))
const canManageDeleted = computed(() => canSeeDeleted.value && hasAssociationContext.value)
const canBulkReview = computed(() => canSeeDeleted.value && hasAssociationContext.value)
const filtered = computed(() => {
  if (!sortKey.value) return items.value
  const list = [...items.value]
  const factor = sortDir.value === 'asc' ? 1 : -1
  list.sort((left, right) => {
    const a = sortKey.value === 'completeness' ? left.completeness : left.updatedAt
    const b = sortKey.value === 'completeness' ? right.completeness : right.updatedAt
    return (a < b ? -1 : a > b ? 1 : 0) * factor
  })
  return list
})
const pendingReviewSelected = computed(() => filtered.value.filter((item) => selectedIds.value.has(item.id) && item.status === '待审核').length)
const updatedAtLabel = computed(() => loadedAt.value
  ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(loadedAt.value)
  : '—')
const statusCode = computed(() => ({ 已认证: 'ACTIVE', 待完善: 'INCOMPLETE', 待审核: 'PENDING_REVIEW', 已停用: 'DISABLED', 已删除: 'DELETED' }[status.value] || ''))
const memberLoadGate = createLatestRequestGate()
let searchTimer: number | null = null

async function load() {
  const requestEpoch = memberLoadGate.begin()
  loading.value = true; error.value = null
  try {
    const result = await platformApi.members(keyword.value.trim(), statusCode.value, page.value, size.value, includeDeleted.value)
    if (!memberLoadGate.isCurrent(requestEpoch)) return
    if (!result.items.length && result.total > 0 && result.page > 0) {
      page.value = Math.max(0, Math.ceil(result.total / result.size) - 1)
      await load()
      return
    }
    items.value = result.items; total.value = result.total; page.value = result.page; size.value = result.size
    loadedAt.value = new Date()
    selectedIds.value = new Set()
  } catch (reason) {
    if (memberLoadGate.isCurrent(requestEpoch)) error.value = safePageResourceError(reason)
  }
  finally {
    if (memberLoadGate.isCurrent(requestEpoch)) loading.value = false
  }
}

function toggleSort(key: 'completeness' | 'updatedAt') {
  if (sortKey.value === key) {
    if (sortDir.value === 'desc') sortDir.value = 'asc'
    else { sortKey.value = ''; sortDir.value = 'desc' }
  } else { sortKey.value = key; sortDir.value = 'desc' }
}

function sortAria(key: 'completeness' | 'updatedAt'): 'ascending' | 'descending' | 'none' {
  if (sortKey.value !== key) return 'none'
  return sortDir.value === 'asc' ? 'ascending' : 'descending'
}

function toggleSelect(id: string) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  selectedIds.value = selectedIds.value.size === filtered.value.length
    ? new Set()
    : new Set(filtered.value.map((item) => item.id))
}

async function bulkReview() {
  const targets = filtered.value.filter((item) => selectedIds.value.has(item.id) && item.status === '待审核')
  if (!targets.length || bulkBusy.value) return
  if (!window.confirm(`确认将 ${targets.length} 家待审核企业批量通过审核？该操作会逐条写入审计记录。`)) return
  bulkBusy.value = true; importMessage.value = null
  let success = 0
  let failed = 0
  for (const item of targets) {
    try {
      const { etag } = await platformApi.member(item.id)
      await platformApi.reviewMember(item.id, 'ACTIVE', '批量审核通过', etag)
      success += 1
    } catch { failed += 1 }
  }
  importMessage.value = failed
    ? `批量审核完成：${success} 家通过，${failed} 家因数据变化或权限失败，请刷新后重试。`
    : `已完成批量审核：${success} 家企业通过审核。`
  bulkBusy.value = false
  await load()
}

function displayMemberStatus(value: string): string {
  return ({ ACTIVE: '已认证', PENDING_REVIEW: '待审核', INCOMPLETE: '待完善', DISABLED: '已停用', DELETED: '已删除' } as Record<string, string>)[value] || value
}

function visibilityLabel(value: string): string {
  return ({ PRIVATE: '仅本单位', ASSOCIATION: '本协会', PARTNERS: '友好协会', MEMBERS: '全体会员', PUBLIC: '公开' } as Record<string, string>)[value] || value
}

function clearFilters() {
  keyword.value = ''
  status.value = '全部'
  includeDeleted.value = false
  page.value = 0
}

function exportCsv() {
  const rows = filtered.value
  if (!rows.length) return
  const header = ['企业名称', '统一信用代码', '业务类别', '场景', '主要产品与服务', '服务区域', '联系人', '资料完整度(%)', '认证状态', '更新时间']
  const escapeCell = (value: string) => `"${value.replaceAll('"', '""')}"`
  const lines = rows.map((item) => [
    item.name, item.unifiedSocialCreditCode || '', item.role, item.scenes.join('、'),
    item.products.join('、'), item.city, item.contact, String(item.completeness),
    item.status, formatDateTime(item.updatedAt),
  ].map(escapeCell).join(','))
  const csv = `\ufeff${header.map(escapeCell).join(',')}\n${lines.join('\n')}`
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `会员企业_筛选结果_${new Date().toISOString().slice(0, 10)}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
  importMessage.value = `已导出当前页 ${rows.length} 家企业（CSV）。`
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }

watch(includeDeleted, (value) => {
  status.value = normalizeMemberDeletedStatus(status.value, value)
})
watch([keyword, status, includeDeleted], () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { page.value = 0; void load() }, 300)
})
onBeforeUnmount(() => {
  memberLoadGate.invalidate()
  if (searchTimer !== null) window.clearTimeout(searchTimer)
})

function importError(reason: unknown): string {
  if (reason instanceof ApiRequestError) {
    if (reason.status === 403) return '当前账号没有批量采集权限。'
    if (reason.status === 413) return '文件过大，请控制在 5 MiB 以内。'
    if (reason.code === 'INVALID_MEMBER_IMPORT') return '文件格式或表头不符合调查模板，请下载最新模板后重试。'
  }
  return '操作失败，请稍后重试。'
}

async function downloadTemplate() {
  if (importBusy.value) return
  importBusy.value = true
  importMessage.value = null
  try {
    const blob = await platformApi.downloadMemberTemplate()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = '北京地下管线协会会员企业调查表.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
    importMessage.value = '调查模板已下载。'
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}

function chooseImportFile() {
  if (!canCollect.value) {
    importMessage.value = '系统管理员需先选择协会，才能导入会员企业资料。'
    return
  }
  fileInput.value?.click()
}
async function previewFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!canCollect.value) {
    importMessage.value = '系统管理员需先选择协会，才能导入会员企业资料。'
    return
  }
  if (!file || importBusy.value) return
  importBusy.value = true
  importMessage.value = null
  importPreview.value = null
  try {
    importPreview.value = await platformApi.previewMemberImport(
      file,
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
    importMessage.value = `预检完成：${importPreview.value.validRows} 行可导入，${importPreview.value.invalidRows} 行需修正。`
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}
async function commitImport() {
  if (!canCollect.value) {
    importMessage.value = '系统管理员需先选择协会，才能导入会员企业资料。'
    return
  }
  if (!importPreview.value || importBusy.value || importPreview.value.validRows === 0) return
  importBusy.value = true
  importMessage.value = null
  try {
    const result = await platformApi.commitMemberImport(importPreview.value.batchId)
    const resultMessage = `已导入 ${result.importedRows} 家企业，统一进入待审核；${result.invalidRows} 行未导入。`
    importMessage.value = resultMessage
    importPreview.value = { ...importPreview.value, status: 'COMMITTED' }
    await load()
    importMessage.value = error.value
      ? `${resultMessage} 列表刷新失败，请点击“重新加载”核对最新结果。`
      : resultMessage
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}

async function viewMember(id: string, includeDeleted = false) {
  if (viewBusy.value) return
  viewBusy.value = true; importMessage.value = null
  try { viewing.value = (await platformApi.member(id, includeDeleted)).member }
  catch (reason) { importMessage.value = importError(reason) }
  finally { viewBusy.value = false }
}

async function toggleDeleted(item: typeof items.value[number]) {
  if (!canManageDeleted.value) {
    importMessage.value = '系统管理员需先选择协会，才能删除或恢复会员企业。'
    return
  }
  if (importBusy.value) return
  if (!item.deletedAt && !window.confirm(`确认将“${item.name}”移入回收状态？企业资料不会被物理删除，可随后恢复。`)) return
  importBusy.value = true; importMessage.value = null
  try {
    if (item.deletedAt) {
      await platformApi.restoreMember(item.id, item.version)
      importMessage.value = '会员企业及其完整资料已恢复。'
    } else {
      await platformApi.deleteMember(item.id, item.version)
      importMessage.value = '会员企业已移入回收状态。'
    }
    await load()
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}

onMounted(async () => {
  await load()
  if (route.query.action === 'import' && canCollect.value) {
    importGuide.value = true
    importMessage.value = '请选择按最新调查模板填写的 Excel；上传后会先预检，不会直接入库。'
    await nextTick(); importButton.value?.focus()
  }
})
</script>

<template>
  <div>
    <PageHeader eyebrow="MEMBER ENTERPRISES" title="会员企业" description="统一沉淀会员画像、产品服务与场景能力；认证状态、资料来源全程可查">
      <template v-if="canCollectRole">
        <button class="secondary-button" :disabled="importBusy" @click="downloadTemplate">下载调查模板</button>
        <button ref="importButton" class="secondary-button" :disabled="importBusy || !canCollect" @click="chooseImportFile">批量导入</button>
        <button class="secondary-button" :disabled="!filtered.length" aria-label="导出当前页企业列表为 CSV" @click="exportCsv">导出本页</button>
        <RouterLink v-if="canCollect" class="primary-button" to="/members/new">+ 新增企业</RouterLink>
        <button v-else class="primary-button" type="button" disabled title="请先选择协会" @click="chooseImportFile">+ 新增企业</button>
        <input ref="fileInput" class="visually-hidden" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="previewFile" />
      </template>
    </PageHeader>
    <ProfileReviewQueue v-if="canCollect" />

    <div v-if="canCollectRole && !hasAssociationContext" class="save-message page-message" role="status">系统管理员需先在管理上下文中选择协会，才能新增、导入、删除或恢复会员企业。</div>
    <div v-if="importMessage" class="save-message import-message" aria-live="polite">{{ importMessage }}</div>
    <section v-if="canCollect && importGuide && !importPreview" class="panel import-guide"><div><h3>导入会员企业资料</h3><p>1. 下载最新模板 → 2. 选择已填写的 .xlsx → 3. 核对预检结果 → 4. 确认导入。</p></div><div class="inline-actions"><button class="secondary-button" :disabled="importBusy" @click="downloadTemplate">下载模板</button><button class="primary-button" :disabled="importBusy" @click="chooseImportFile">选择 Excel 文件</button><button class="text-button" @click="importGuide = false">收起引导</button></div></section>
    <section v-if="canCollect && importPreview" class="panel import-preview-panel">
      <div class="import-preview-head">
        <div><h3>导入预检</h3><p>{{ importPreview.filename }} · {{ importPreview.submittedUnit }} · 模板 {{ importPreview.templateVersion }} · 共 {{ importPreview.totalRows }} 行</p></div>
        <div class="import-counts"><strong>{{ importPreview.validRows }}</strong><span>可导入</span><strong class="danger-text">{{ importPreview.invalidRows }}</strong><span>需修正</span></div>
      </div>
      <div class="data-table-wrap import-row-table"><table class="data-table"><thead><tr><th>Excel 行</th><th>企业名称</th><th>分类</th><th>结果</th></tr></thead><tbody><tr v-for="row in importPreview.rows" :key="row.rowNumber"><td>{{ row.rowNumber }}</td><td>{{ row.data.name || '—' }}</td><td>{{ row.data.category || '—' }}</td><td><span v-if="row.status === 'VALID'" class="valid-text">可导入</span><span v-else class="danger-text">{{ row.errors.join('；') }}</span></td></tr></tbody></table></div>
      <div class="form-actions"><button class="text-button" type="button" @click="importPreview = null">关闭</button><button class="primary-button" type="button" :disabled="importBusy || importPreview.validRows === 0 || importPreview.status !== 'PREVIEWED'" @click="commitImport">{{ importPreview.status === 'COMMITTED' ? '已提交' : (importBusy ? '正在导入…' : `确认导入 ${importPreview.validRows} 家`) }}</button></div>
    </section>

    <section class="panel filter-panel">
      <div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索企业名称、业务角色或产品服务" aria-label="搜索会员企业" /></div>
      <select v-model="status" class="filter-select" aria-label="按认证状态筛选"><option>全部</option><option>已认证</option><option>待完善</option><option>待审核</option><option>已停用</option><option v-if="includeDeleted">已删除</option></select>
      <label v-if="canSeeDeleted" class="checkbox-field"><input v-model="includeDeleted" type="checkbox" /> 包含已删除</label>
      <span class="result-count">共 {{ total }} 家企业</span>
    </section>
    <div v-if="canBulkReview && selectedIds.size" class="bulk-bar" role="region" aria-label="批量操作">
      <strong>已选 {{ selectedIds.size }} 家</strong>
      <small v-if="pendingReviewSelected">其中待审核 {{ pendingReviewSelected }} 家</small>
      <small v-else>所选企业中没有待审核项</small>
      <span class="spacer">
        <button class="secondary-button small" type="button" :disabled="bulkBusy" @click="selectedIds = new Set()">取消选择</button>
        <button class="primary-button small" type="button" :disabled="bulkBusy || !pendingReviewSelected" @click="bulkReview">{{ bulkBusy ? '正在审核…' : `批量通过审核（${pendingReviewSelected}）` }}</button>
      </span>
    </div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="panel flush-panel">
      <div v-if="filtered.length" class="data-table-wrap"><table class="data-table member-table"><thead><tr><th v-if="canBulkReview" class="select-cell"><input type="checkbox" aria-label="全选本页企业" :checked="selectedIds.size === filtered.length && filtered.length > 0" @change="toggleSelectAll" /></th><th>企业</th><th>业务角色 / 场景</th><th>主要产品与服务</th><th class="sortable" :aria-sort="sortAria('completeness')"><button type="button" @click="toggleSort('completeness')">资料完整度 <span class="sort-mark" aria-hidden="true">{{ sortKey === 'completeness' ? (sortDir === 'asc' ? '▲' : '▼') : '⇅' }}</span></button></th><th>状态</th><th class="sortable" :aria-sort="sortAria('updatedAt')"><button type="button" @click="toggleSort('updatedAt')">更新日期 <span class="sort-mark" aria-hidden="true">{{ sortKey === 'updatedAt' ? (sortDir === 'asc' ? '▲' : '▼') : '⇅' }}</span></button></th><th></th></tr></thead><tbody>
        <tr v-for="item in filtered" :key="item.id"><td v-if="canBulkReview" class="select-cell" data-label="选择"><input type="checkbox" :aria-label="`选择${item.name}`" :checked="selectedIds.has(item.id)" @change="toggleSelect(item.id)" /></td><td data-label="企业"><div class="enterprise-cell"><span class="enterprise-logo">{{ item.shortName.slice(0, 2) }}</span><div><strong>{{ item.name }}</strong><small>{{ item.city || '未填写地址' }} · 信用代码：{{ item.unifiedSocialCreditCode || '未登记' }} · 联系人：{{ item.contact || '未填写' }}</small></div></div></td><td data-label="业务角色 / 场景"><span class="table-muted">{{ item.role }}</span><div class="tags"><span v-for="scene in item.scenes" :key="scene">{{ scene }}</span></div></td><td data-label="主要产品与服务">{{ item.products.join('、') || '—' }}</td><td data-label="资料完整度"><div class="completion-cell"><div class="progress-track"><i :style="{ width: `${item.completeness}%` }" /></div><strong>{{ item.completeness }}%</strong></div></td><td data-label="状态"><StatusBadge :value="item.status" /></td><td data-label="更新日期" class="table-muted">{{ formatDateTime(item.updatedAt) }}</td><td data-label="操作"><div class="inline-actions"><button class="text-button" :disabled="viewBusy" @click="viewMember(item.id, Boolean(item.deletedAt))">查看</button><RouterLink v-if="hasAssociationContext && (item.canEdit || item.canReview)" class="row-action" :to="`/members/${item.id}/edit`">{{ item.canReview && item.status === '待审核' ? '审核' : '编辑' }}</RouterLink><button v-if="canManageDeleted" class="text-button" :class="{ 'danger-text': !item.deletedAt }" :disabled="importBusy" @click="toggleDeleted(item)">{{ item.deletedAt ? '恢复' : '删除' }}</button></div></td></tr>
      </tbody></table></div>
      <div v-else class="empty-business-state"><b>{{ keyword.trim() || status !== '全部' || includeDeleted ? '没有符合条件的企业' : '暂无会员企业' }}</b><span>{{ keyword.trim() || status !== '全部' || includeDeleted ? '请调整搜索条件或筛选范围后重试。' : '请通过新增企业或调查表批量导入建立真实企业资料。' }}</span><div class="inline-actions"><button class="secondary-button small" type="button" @click="clearFilters">清除筛选</button><RouterLink v-if="canCollect" class="primary-button small" to="/members/new">+ 新增企业</RouterLink></div></div>
      <div class="data-source panel-source"><span>数据来源：<b>会员企业档案库</b></span><span>更新时间：<b>{{ updatedAtLabel }}</b></span><span>可见范围：<b>按当前账号数据域</b></span><span>状态口径：<b>已认证 / 待审核 / 待完善 / 已停用</b></span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <div v-if="viewing" class="modal-backdrop" role="dialog" aria-modal="true" :aria-label="`${viewing.name} 企业详情`" @click.self="viewing = null"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">MEMBER PROFILE</span><h2>{{ viewing.name }}</h2></div><button class="icon-button" aria-label="关闭会员详情" @click="viewing = null">×</button></div><div class="detail-grid"><div><span>单位类别</span><strong>{{ viewing.category }}</strong></div><div><span>统一信用代码</span><strong>{{ viewing.unifiedSocialCreditCode || '—' }}</strong></div><div><span>联系人</span><strong>{{ viewing.contactName || '—' }}</strong></div><div><span>联系电话</span><strong>{{ viewing.contactPhone || '—' }}</strong></div><div><span>联系邮箱</span><strong>{{ viewing.contactEmail || '—' }}</strong></div><div><span>认证状态</span><strong>{{ displayMemberStatus(viewing.status) }}</strong></div><div><span>可见范围</span><strong>{{ visibilityLabel(viewing.visibility) }}</strong></div><div><span>档案版本</span><strong>v{{ viewing.version }}</strong></div><div><span>更新时间</span><strong>{{ formatDateTime(viewing.updatedAt) }}</strong></div></div><div class="modal-copy"><h3>企业简介</h3><p>{{ viewing.introduction || '暂无简介' }}</p><h3>核心能力</h3><div class="tags"><span v-for="value in viewing.capabilities" :key="value">{{ value }}</span></div><h3>产品</h3><div class="tags"><span v-for="value in viewing.products" :key="value">{{ value }}</span></div><h3>服务</h3><div class="tags"><span v-for="value in viewing.services" :key="value">{{ value }}</span></div><h3>应用场景</h3><div class="tags"><span v-for="value in viewing.applicationScenarios" :key="value">{{ value }}</span></div><h3>合作需求</h3><div class="tags"><span v-for="value in viewing.cooperationNeeds" :key="value">{{ value }}</span></div></div></section></div>
  </div>
</template>
