<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { Attachment } from '../types/domain'
import { apiActionMessage, formatDateTime } from './business-form'

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
const visibility = ref('PRIVATE')
const keyword = ref('')
const canWrite = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || ''))
const canSeeDeleted = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || ''))
const canIngestKnowledge = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
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

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]; input.value = ''
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
    message.value = `已上传 ${saved.originalFilename}，扫描状态：${saved.scanStatus}。`
  } catch (reason) { message.value = apiActionMessage(reason, '附件上传失败，请检查文件大小和类型。') }
  finally { busy.value = false }
}

async function download(item: Attachment) {
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
  knowledgeItem.value = item
  knowledgeTitle.value = item.originalFilename.replace(/\.[^.]+$/, '')
}

async function ingestKnowledge() {
  if (!knowledgeItem.value || !knowledgeTitle.value.trim() || busy.value) return
  busy.value = true; message.value = ''
  try {
    const result = await platformApi.ingestKnowledgeFile(
      knowledgeItem.value.id,
      knowledgeTitle.value.trim(),
      auth.user.value?.role === 'SYSTEM_ADMIN' ? auth.user.value.associationId || undefined : undefined,
    )
    knowledgeItem.value = null
    message.value = `资料已解析并纳入政策知识库：${result.chunkCount} 个片段，版本 ${result.version}。`
  } catch (reason) { message.value = apiActionMessage(reason, '资料解析或知识库入库失败。') }
  finally { busy.value = false }
}

function sizeLabel(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="DOCUMENT ASSETS" title="资料附件" description="附件按企业和可见范围受控存储，支持安全下载、删除与恢复">
      <select v-if="canWrite" v-model="visibility" class="filter-select"><option value="PRIVATE">仅本企业与协会</option><option value="ASSOCIATION">本协会</option></select>
      <button v-if="canWrite" class="primary-button" :disabled="busy" @click="fileInput?.click()">{{ busy ? '处理中…' : '+ 上传附件' }}</button>
      <input ref="fileInput" class="visually-hidden" type="file" @change="upload" />
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="panel filter-panel"><div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索当前页文件名、类型或扫描状态" /></div><span class="result-count">本页 {{ filtered.length }} 个，共 {{ total }} 个附件</span></section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="panel flush-panel">
      <div v-if="items.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>文件</th><th>大小</th><th>扫描</th><th>可见范围</th><th>上传时间</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filtered" :key="item.id"><td><strong>{{ item.originalFilename }}</strong><small class="table-subline">{{ item.mediaType }}</small></td><td>{{ sizeLabel(item.sizeBytes) }}</td><td><StatusBadge :value="item.scanStatus" /></td><td>{{ item.visibility }}</td><td>{{ formatDateTime(item.uploadedAt) }}</td><td>{{ item.deletedAt ? '已删除' : '有效' }}</td><td><div class="inline-actions"><button class="text-button" @click="download(item)">下载</button><button v-if="canIngestKnowledge && !item.deletedAt" class="text-button" @click="openKnowledgeIngestion(item)">纳入知识库</button><button v-if="canWrite" class="text-button" :class="{ 'danger-text': !item.deletedAt }" @click="toggle(item)">{{ item.deletedAt ? '恢复' : '删除' }}</button></div></td></tr></tbody></table></div>
      <div v-else class="empty-business-state"><b>暂无附件</b><span>企业资质、产品资料、协作成果等文件可在此统一管理。</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>
    <div v-if="knowledgeItem" class="modal-backdrop" @click.self="knowledgeItem = null"><form class="panel modal-card compact-modal" @submit.prevent="ingestKnowledge"><div class="modal-head"><div><span class="eyebrow">KNOWLEDGE INGESTION</span><h2>纳入政策知识库</h2></div><button type="button" class="icon-button" @click="knowledgeItem = null">×</button></div><div class="modal-copy"><p>系统将解析 {{ knowledgeItem.originalFilename }}，保存分段及来源关系，问答结果可回溯到该附件。</p><label><span>资料标题 *</span><input v-model="knowledgeTitle" required maxlength="300" /></label><small>支持 PDF、DOCX、XLSX、TXT 和 CSV，最大 20 MiB。</small></div><div class="form-actions"><button type="button" class="secondary-button" @click="knowledgeItem = null">取消</button><button class="primary-button" :disabled="busy || !knowledgeTitle.trim()">{{ busy ? '解析中…' : '确认入库' }}</button></div></form></div>
  </div>
</template>
