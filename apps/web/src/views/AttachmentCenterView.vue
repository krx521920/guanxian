<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { Attachment, KnowledgeDocument } from '../types/domain'
import { apiActionMessage, formatDateTime } from './business-form'
import { attachmentValidationLabel, hasAssociationWriteContext, isAttachmentContentAvailable, isKnowledgeAttachmentSupported } from './business-view-guards'

const auth = useAuth()
const items = ref<Attachment[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const knowledgeItem = ref<Attachment | null>(null)
const knowledgeTitle = ref('')
const knowledgeDocuments = ref<KnowledgeDocument[]>([])
const knowledgeTotal = ref(0)
const knowledgePage = ref(0)
const knowledgeSize = ref(20)
const includeDeletedKnowledge = ref(false)
const visibility = ref('PRIVATE')
const keyword = ref('')
const role = computed(() => auth.user.value?.role || '')
const hasAssociationContext = computed(() => hasAssociationWriteContext(role.value, auth.user.value?.associationId))
const canWriteRole = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(role.value))
const canWrite = computed(() => canWriteRole.value && hasAssociationContext.value)
const canSeeDeleted = computed(() => canWriteRole.value)
const canIngestKnowledgeRole = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(role.value))
const canIngestKnowledge = computed(() => canIngestKnowledgeRole.value && hasAssociationContext.value)
const maxAttachmentBytes = 20 * 1024 * 1024
const filtered = computed(() => items.value.filter((item) => `${item.originalFilename}${item.mediaType}${item.scanStatus}`.toLowerCase().includes(keyword.value.toLowerCase())))

async function load() {
  loading.value = true; error.value = null
  try {
    const result = await platformApi.attachments(auth.user.value?.enterpriseId || undefined, canSeeDeleted.value, page.value, size.value)
    items.value = result.items; total.value = result.total; page.value = result.page; size.value = result.size
    if (!result.items.length && result.total > 0 && page.value > 0) { page.value -= 1; await load() }
  }
  catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
}

async function loadKnowledge() {
  if (!canIngestKnowledge.value) {
    knowledgeDocuments.value = []
    knowledgeTotal.value = 0
    return
  }
  try {
    const result = await platformApi.knowledgeDocuments(
      includeDeletedKnowledge.value, knowledgePage.value, knowledgeSize.value,
    )
    knowledgeDocuments.value = result.items
    knowledgeTotal.value = result.total
    knowledgePage.value = result.page
    knowledgeSize.value = result.size
    if (!result.items.length && result.total > 0 && result.page > 0) {
      knowledgePage.value = Math.max(0, Math.ceil(result.total / result.size) - 1)
      await loadKnowledge()
    }
  } catch (reason) {
    message.value = apiActionMessage(reason, '知识文档列表加载失败。')
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]; input.value = ''
  if (!canWrite.value) {
    message.value = '系统管理员需先选择协会，才能上传附件。'
    return
  }
  if (!file || busy.value) return
  if (file.size > maxAttachmentBytes) {
    message.value = '附件不能超过 20 MiB。'
    return
  }
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.uploadAttachment(
      file,
      visibility.value,
      auth.user.value?.enterpriseId || undefined,
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
    page.value = 0; await load()
    message.value = `已上传 ${saved.originalFilename}，内容校验状态：${attachmentValidationLabel(saved.scanStatus)}。`
  } catch (reason) { message.value = apiActionMessage(reason, '附件上传失败，请检查文件大小和类型。') }
  finally { busy.value = false }
}

async function download(item: Attachment) {
  if (!isAttachmentContentAvailable(item)) {
    message.value = item.deletedAt
      ? '已删除附件不可下载，请先恢复附件。'
      : '该附件未通过当前版本的内容校验，请重新上传后再下载。'
    return
  }
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const blob = await platformApi.downloadAttachment(item.id)
    const url = URL.createObjectURL(blob); const anchor = document.createElement('a')
    anchor.href = url; anchor.download = item.originalFilename; anchor.click(); URL.revokeObjectURL(url)
    message.value = `已下载 ${item.originalFilename}。`
  } catch (reason) { message.value = apiActionMessage(reason, '附件下载失败。') }
  finally { busy.value = false }
}

async function toggle(item: Attachment) {
  if (!canWrite.value) {
    message.value = '系统管理员需先选择协会，才能删除或恢复附件。'
    return
  }
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = item.deletedAt ? await platformApi.restoreAttachment(item) : await platformApi.removeAttachment(item)
    items.value = items.value.map((value) => value.id === saved.id ? saved : value)
    message.value = item.deletedAt ? '附件已恢复。' : '附件已移入回收状态。'
  } catch (reason) { message.value = apiActionMessage(reason, '附件状态更新失败。') }
  finally { busy.value = false }
}

function openKnowledgeIngestion(item: Attachment) {
  if (!canIngestKnowledge.value) {
    message.value = '系统管理员需先选择协会，才能将资料纳入知识库。'
    return
  }
  if (!isKnowledgeAttachmentSupported(item)) return
  knowledgeItem.value = item
  knowledgeTitle.value = item.originalFilename.replace(/\.[^.]+$/, '')
}

async function ingestKnowledge() {
  if (!canIngestKnowledge.value) {
    knowledgeItem.value = null
    message.value = '系统管理员需先选择协会，才能将资料纳入知识库。'
    return
  }
  if (knowledgeItem.value && !isKnowledgeAttachmentSupported(knowledgeItem.value)) {
    knowledgeItem.value = null
    message.value = '仅支持将 PDF、DOCX、XLSX、TXT 或 CSV 文件纳入知识库。'
    return
  }
  if (!knowledgeItem.value || !knowledgeTitle.value.trim() || busy.value) return
  busy.value = true; message.value = ''
  try {
    const result = await platformApi.ingestKnowledgeFile(
      knowledgeItem.value.id,
      knowledgeTitle.value.trim(),
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
    knowledgeItem.value = null
    await loadKnowledge()
    message.value = `资料已解析为 ${result.chunkCount} 个片段并保存为草稿；提交并经协会审核后才会用于问答。`
  } catch (reason) { message.value = apiActionMessage(reason, '资料解析或知识库入库失败。') }
  finally { busy.value = false }
}

async function knowledgeAction(item: KnowledgeDocument, action: string) {
  if (busy.value) return
  let approved = false
  let comment = ''
  if (action === 'approve') approved = true
  if (action === 'reject') {
    comment = window.prompt('请填写退回原因')?.trim() || ''
    if (!comment) { message.value = '退回知识文档必须填写审核意见。'; return }
  }
  if (action === 'delete' && !window.confirm(`确认删除知识文档“${item.title}”？可在回收状态中恢复。`)) return
  busy.value = true; message.value = ''
  try {
    if (action === 'submit') await platformApi.submitKnowledgeDocument(item)
    else if (action === 'approve' || action === 'reject') await platformApi.reviewKnowledgeDocument(item, approved, comment)
    else if (action === 'disable') await platformApi.disableKnowledgeDocument(item)
    else if (action === 'archive') await platformApi.archiveKnowledgeDocument(item)
    else if (action === 'delete') await platformApi.removeKnowledgeDocument(item)
    else if (action === 'restore') await platformApi.restoreKnowledgeDocument(item)
    else if (action === 'reparse') await platformApi.reparseKnowledgeDocument(item)
    else if (action === 'reembed') await platformApi.reembedKnowledgeDocument(item)
    else throw new Error('unsupported knowledge action')
    await loadKnowledge()
    message.value = action === 'reparse'
      ? '文档已重新解析并回到草稿，需重新提交审核。'
      : action === 'reembed' ? '当前版本的 Embedding 已重新生成并记录执行审计。' : '知识文档状态已更新。'
  } catch (reason) { message.value = apiActionMessage(reason, '知识文档操作失败。') }
  finally { busy.value = false }
}

function sizeLabel(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }
function changeKnowledgePage(value: number) { knowledgePage.value = value; void loadKnowledge() }
function resizeKnowledgePage(value: number) { knowledgeSize.value = value; knowledgePage.value = 0; void loadKnowledge() }

onMounted(async () => { await Promise.all([load(), loadKnowledge()]) })
</script>

<template>
  <div>
    <PageHeader eyebrow="DOCUMENT ASSETS" title="资料附件" description="附件按企业和可见范围受控存储，通过大小、类型、签名与完整性校验后才可下载或入库">
      <select v-if="canWriteRole" v-model="visibility" class="filter-select" :disabled="!canWrite"><option value="PRIVATE">仅本企业与协会</option><option value="ASSOCIATION">本协会</option></select>
      <button v-if="canWriteRole" class="primary-button" :disabled="busy || !canWrite" @click="fileInput?.click()">{{ busy ? '处理中…' : '+ 上传附件' }}</button>
      <input ref="fileInput" class="visually-hidden" type="file" @change="upload" />
    </PageHeader>
    <div v-if="canWriteRole && !hasAssociationContext" class="save-message page-message" role="status">系统管理员需先在管理上下文中选择协会，才能上传、删除、恢复附件或将资料纳入知识库。</div>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="panel filter-panel"><div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索当前页文件名、类型或内容校验状态" /></div><span class="result-count">本页 {{ filtered.length }} 个，共 {{ total }} 个附件</span></section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="panel flush-panel">
      <div v-if="items.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>文件</th><th>大小</th><th>内容校验</th><th>可见范围</th><th>上传时间</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filtered" :key="item.id"><td><strong>{{ item.originalFilename }}</strong><small class="table-subline">{{ item.mediaType }}</small></td><td>{{ sizeLabel(item.sizeBytes) }}</td><td><StatusBadge :value="attachmentValidationLabel(item.scanStatus)" /><small v-if="!item.deletedAt && !isAttachmentContentAvailable(item)" class="table-subline">请重新上传</small></td><td>{{ item.visibility }}</td><td>{{ formatDateTime(item.uploadedAt) }}</td><td>{{ item.deletedAt ? '已删除' : '有效' }}</td><td><div class="inline-actions"><button v-if="isAttachmentContentAvailable(item)" class="text-button" @click="download(item)">下载</button><button v-if="canIngestKnowledge && isKnowledgeAttachmentSupported(item)" class="text-button" @click="openKnowledgeIngestion(item)">纳入知识库</button><button v-if="canWrite" class="text-button" :class="{ 'danger-text': !item.deletedAt }" @click="toggle(item)">{{ item.deletedAt ? '恢复' : '删除' }}</button></div></td></tr></tbody></table></div>
      <div v-else class="empty-business-state"><b>暂无附件</b><span>企业资质、产品资料、协作成果等文件可在此统一管理。</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <section v-if="canIngestKnowledge" class="panel flush-panel knowledge-document-panel">
      <div class="section-heading"><div><span class="eyebrow">KNOWLEDGE GOVERNANCE</span><h2>知识文档审核与版本</h2><p>共 {{ knowledgeTotal }} 份；只有已发布且未删除的当前版本会参与当前协会问答。</p></div><label class="checkbox-field"><input v-model="includeDeletedKnowledge" type="checkbox" @change="knowledgePage = 0; loadKnowledge()" /> 包含已删除</label></div>
      <div v-if="knowledgeDocuments.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>文档</th><th>版本 / 分段</th><th>Embedding</th><th>审核状态</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="item in knowledgeDocuments" :key="item.id"><td><strong>{{ item.title }}</strong><small class="table-subline">{{ item.sourceFilename || item.sourceType }} · {{ item.visibility }}</small></td><td>内容 v{{ item.currentVersion }} · {{ item.chunkCount }} 段<small class="table-subline">控制版本 {{ item.lifecycleVersion }}</small></td><td><StatusBadge :value="item.embeddingStatus" /></td><td><StatusBadge :value="item.deletedAt ? '已删除' : item.status" /><small v-if="item.reviewComment" class="table-subline">{{ item.reviewComment }}</small></td><td>{{ formatDateTime(item.updatedAt) }}</td><td><div class="inline-actions"><button v-if="!item.deletedAt && item.status === 'DRAFT'" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'submit')">提交审核</button><button v-if="!item.deletedAt && item.status === 'PENDING_REVIEW'" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'approve')">审核通过</button><button v-if="!item.deletedAt && item.status === 'PENDING_REVIEW'" class="text-button danger-text" :disabled="busy" @click="knowledgeAction(item, 'reject')">退回</button><button v-if="!item.deletedAt && item.status === 'PUBLISHED'" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'disable')">停用</button><button v-if="!item.deletedAt && ['PUBLISHED', 'DISABLED'].includes(item.status)" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'archive')">归档</button><button v-if="!item.deletedAt && item.sourceFileId" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'reparse')">重新解析</button><button v-if="!item.deletedAt" class="text-button" :disabled="busy" @click="knowledgeAction(item, 'reembed')">重建向量</button><button v-if="!item.deletedAt" class="text-button danger-text" :disabled="busy" @click="knowledgeAction(item, 'delete')">删除</button><button v-else class="text-button" :disabled="busy" @click="knowledgeAction(item, 'restore')">恢复为草稿</button></div></td></tr></tbody></table></div>
      <div v-else class="empty-business-state"><b>暂无知识文档</b><span>请先从已完成内容扫描的附件创建知识草稿。</span></div>
      <PaginationBar :page="knowledgePage" :size="knowledgeSize" :total="knowledgeTotal" :disabled="busy" @change="changeKnowledgePage" @resize="resizeKnowledgePage" />
    </section>
    <div v-if="knowledgeItem && canIngestKnowledge" class="modal-backdrop" @click.self="knowledgeItem = null"><form class="panel modal-card compact-modal" @submit.prevent="ingestKnowledge"><div class="modal-head"><div><span class="eyebrow">KNOWLEDGE INGESTION</span><h2>创建知识草稿</h2></div><button type="button" class="icon-button" @click="knowledgeItem = null">×</button></div><div class="modal-copy"><p>系统将解析 {{ knowledgeItem.originalFilename }}，保存分段及来源关系；新文档先进入草稿，审核发布后才能参与问答。</p><label><span>资料标题 *</span><input v-model="knowledgeTitle" required maxlength="300" /></label><small>支持 PDF、DOCX、XLSX、TXT 和 CSV，最大 20 MiB。</small></div><div class="form-actions"><button type="button" class="secondary-button" @click="knowledgeItem = null">取消</button><button class="primary-button" :disabled="busy || !knowledgeTitle.trim()">{{ busy ? '解析中…' : '创建草稿' }}</button></div></form></div>
  </div>
</template>
