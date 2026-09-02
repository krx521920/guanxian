<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { createLatestRequestGate } from '../services/latest-request'
import { platformApi } from '../services/platform-api'
import type { MemberImportPreview, MemberProfile } from '../types/domain'
import { hasAssociationWriteContext, normalizeMemberDeletedStatus } from './business-view-guards'

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
const role = computed(() => auth.user.value?.role || '')
const hasAssociationContext = computed(() => hasAssociationWriteContext(role.value, auth.user.value?.associationId))
const canCollectRole = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(role.value))
const canCollect = computed(() => canCollectRole.value && hasAssociationContext.value)
const canSeeDeleted = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(role.value))
const canManageDeleted = computed(() => canSeeDeleted.value && hasAssociationContext.value)
const filtered = computed(() => items.value)
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
  } catch (reason) {
    if (memberLoadGate.isCurrent(requestEpoch)) error.value = safePageResourceError(reason)
  }
  finally {
    if (memberLoadGate.isCurrent(requestEpoch)) loading.value = false
  }
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
    <PageHeader eyebrow="MEMBER ENTERPRISES" title="会员企业" description="统一沉淀会员画像、产品服务与场景能力">
      <template v-if="canCollectRole">
        <button class="secondary-button" :disabled="importBusy" @click="downloadTemplate">下载调查模板</button>
        <button ref="importButton" class="secondary-button" :disabled="importBusy || !canCollect" @click="chooseImportFile">批量导入</button>
        <RouterLink v-if="canCollect" class="primary-button" to="/members/new">+ 新增企业</RouterLink>
        <button v-else class="primary-button" type="button" disabled title="请先选择协会" @click="chooseImportFile">+ 新增企业</button>
        <input ref="fileInput" class="visually-hidden" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="previewFile" />
      </template>
    </PageHeader>

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
      <div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索企业名称、业务角色或产品服务" /></div>
      <select v-model="status" class="filter-select"><option>全部</option><option>已认证</option><option>待完善</option><option>待审核</option><option>已停用</option><option v-if="includeDeleted">已删除</option></select>
      <label v-if="canSeeDeleted" class="checkbox-field"><input v-model="includeDeleted" type="checkbox" /> 包含已删除</label>
      <span class="result-count">共 {{ total }} 家企业</span>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="panel flush-panel">
      <div class="data-table-wrap"><table class="data-table member-table"><thead><tr><th>企业</th><th>业务角色 / 场景</th><th>主要产品与服务</th><th>资料完整度</th><th>状态</th><th>更新日期</th><th></th></tr></thead><tbody>
        <tr v-for="item in filtered" :key="item.id"><td><div class="enterprise-cell"><span class="enterprise-logo">{{ item.shortName.slice(0, 2) }}</span><div><strong>{{ item.name }}</strong><small>{{ item.city || '未填写地址' }} · 联系人：{{ item.contact || '未填写' }}</small></div></div></td><td><span class="table-muted">{{ item.role }}</span><div class="tags"><span v-for="scene in item.scenes" :key="scene">{{ scene }}</span></div></td><td>{{ item.products.join('、') || '—' }}</td><td><div class="completion-cell"><div class="progress-track"><i :style="{ width: `${item.completeness}%` }" /></div><strong>{{ item.completeness }}%</strong></div></td><td><StatusBadge :value="item.status" /></td><td class="table-muted">{{ item.updatedAt }}</td><td><div class="inline-actions"><button class="text-button" :disabled="viewBusy" @click="viewMember(item.id, Boolean(item.deletedAt))">查看</button><RouterLink v-if="hasAssociationContext && (item.canEdit || item.canReview)" class="row-action" :to="`/members/${item.id}/edit`">{{ item.canReview && item.status === '待审核' ? '审核' : '编辑' }}</RouterLink><button v-if="canManageDeleted" class="text-button" :class="{ 'danger-text': !item.deletedAt }" :disabled="importBusy" @click="toggleDeleted(item)">{{ item.deletedAt ? '恢复' : '删除' }}</button></div></td></tr>
        <tr v-if="!filtered.length"><td colspan="7"><div class="empty-business-state"><b>{{ keyword.trim() || status !== '全部' || includeDeleted ? '没有符合条件的企业' : '暂无会员企业' }}</b><span>{{ keyword.trim() || status !== '全部' || includeDeleted ? '请调整搜索条件或筛选范围后重试。' : '请通过新增企业或调查表批量导入建立真实企业资料。' }}</span></div></td></tr>
      </tbody></table></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <div v-if="viewing" class="modal-backdrop" @click.self="viewing = null"><section class="panel modal-card"><div class="modal-head"><div><span class="eyebrow">MEMBER PROFILE</span><h2>{{ viewing.name }}</h2></div><button class="icon-button" @click="viewing = null">×</button></div><div class="detail-grid"><div><span>单位类别</span><strong>{{ viewing.category }}</strong></div><div><span>信用代码</span><strong>{{ viewing.unifiedSocialCreditCode || '—' }}</strong></div><div><span>联系人</span><strong>{{ viewing.contactName || '—' }}</strong></div><div><span>联系电话</span><strong>{{ viewing.contactPhone || '—' }}</strong></div><div><span>联系邮箱</span><strong>{{ viewing.contactEmail || '—' }}</strong></div></div><div class="modal-copy"><h3>企业简介</h3><p>{{ viewing.introduction || '暂无简介' }}</p><h3>核心能力</h3><div class="tags"><span v-for="value in viewing.capabilities" :key="value">{{ value }}</span></div><h3>产品</h3><div class="tags"><span v-for="value in viewing.products" :key="value">{{ value }}</span></div><h3>服务</h3><div class="tags"><span v-for="value in viewing.services" :key="value">{{ value }}</span></div><h3>应用场景</h3><div class="tags"><span v-for="value in viewing.applicationScenarios" :key="value">{{ value }}</span></div><h3>合作需求</h3><div class="tags"><span v-for="value in viewing.cooperationNeeds" :key="value">{{ value }}</span></div></div></section></div>
  </div>
</template>
