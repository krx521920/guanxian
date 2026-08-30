<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type { MemberImportPreview } from '../types/domain'

const auth = useAuth()
const { data: items, loading, error, load } = useAsyncResource(platformApi.members)
const keyword = ref('')
const status = ref('全部')
const fileInput = ref<HTMLInputElement | null>(null)
const importPreview = ref<MemberImportPreview | null>(null)
const importBusy = ref(false)
const importMessage = ref<string | null>(null)
const canCollect = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const filtered = computed(() => (items.value || []).filter((item) => {
  const hitsKeyword = !keyword.value || `${item.name}${item.role}${item.products.join('')}`.includes(keyword.value)
  return hitsKeyword && (status.value === '全部' || item.status === status.value)
}))

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

function chooseImportFile() { fileInput.value?.click() }
async function previewFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || importBusy.value) return
  importBusy.value = true
  importMessage.value = null
  importPreview.value = null
  try {
    importPreview.value = await platformApi.previewMemberImport(file)
    importMessage.value = `预检完成：${importPreview.value.validRows} 行可导入，${importPreview.value.invalidRows} 行需修正。`
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}
async function commitImport() {
  if (!importPreview.value || importBusy.value || importPreview.value.validRows === 0) return
  importBusy.value = true
  importMessage.value = null
  try {
    const result = await platformApi.commitMemberImport(importPreview.value.batchId)
    importMessage.value = `已导入 ${result.importedRows} 家企业，统一进入待审核；${result.invalidRows} 行未导入。`
    importPreview.value = { ...importPreview.value, status: 'COMMITTED' }
    await load()
  } catch (reason) { importMessage.value = importError(reason) }
  finally { importBusy.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="会员企业" description="统一沉淀会员画像、产品服务与场景能力">
      <template v-if="canCollect">
        <button class="secondary-button" :disabled="importBusy" @click="downloadTemplate">下载调查模板</button>
        <button class="secondary-button" :disabled="importBusy" @click="chooseImportFile">批量导入</button>
        <RouterLink class="primary-button" to="/members/new">+ 新增企业</RouterLink>
        <input ref="fileInput" class="visually-hidden" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="previewFile" />
      </template>
    </PageHeader>

    <div v-if="importMessage" class="save-message import-message" aria-live="polite">{{ importMessage }}</div>
    <section v-if="importPreview" class="panel import-preview-panel">
      <div class="import-preview-head">
        <div><h3>导入预检</h3><p>{{ importPreview.filename }} · 共 {{ importPreview.totalRows }} 行</p></div>
        <div class="import-counts"><strong>{{ importPreview.validRows }}</strong><span>可导入</span><strong class="danger-text">{{ importPreview.invalidRows }}</strong><span>需修正</span></div>
      </div>
      <div class="data-table-wrap import-row-table"><table class="data-table"><thead><tr><th>Excel 行</th><th>企业名称</th><th>分类</th><th>结果</th></tr></thead><tbody><tr v-for="row in importPreview.rows" :key="row.rowNumber"><td>{{ row.rowNumber }}</td><td>{{ row.data.name || '—' }}</td><td>{{ row.data.category || '—' }}</td><td><span v-if="row.status === 'VALID'" class="valid-text">可导入</span><span v-else class="danger-text">{{ row.errors.join('；') }}</span></td></tr></tbody></table></div>
      <div class="form-actions"><button class="text-button" type="button" @click="importPreview = null">关闭</button><button class="primary-button" type="button" :disabled="importBusy || importPreview.validRows === 0 || importPreview.status !== 'PREVIEWED'" @click="commitImport">{{ importPreview.status === 'COMMITTED' ? '已提交' : (importBusy ? '正在导入…' : `确认导入 ${importPreview.validRows} 家`) }}</button></div>
    </section>

    <section class="panel filter-panel">
      <div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索企业名称、业务角色或产品服务" /></div>
      <select v-model="status" class="filter-select"><option>全部</option><option>已认证</option><option>待完善</option><option>待审核</option><option>已停用</option></select>
      <span class="result-count">共 {{ filtered.length }} 家企业</span>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="items" class="panel flush-panel">
      <div class="data-table-wrap"><table class="data-table member-table"><thead><tr><th>企业</th><th>业务角色 / 场景</th><th>主要产品与服务</th><th>资料完整度</th><th>状态</th><th>更新日期</th><th></th></tr></thead><tbody>
        <tr v-for="item in filtered" :key="item.id"><td><div class="enterprise-cell"><span class="enterprise-logo">{{ item.shortName.slice(0, 2) }}</span><div><strong>{{ item.name }}</strong><small>{{ item.city || '未填写地址' }} · 联系人：{{ item.contact || '未填写' }}</small></div></div></td><td><span class="table-muted">{{ item.role }}</span><div class="tags"><span v-for="scene in item.scenes" :key="scene">{{ scene }}</span></div></td><td>{{ item.products.join('、') || '—' }}</td><td><div class="completion-cell"><div class="progress-track"><i :style="{ width: `${item.completeness}%` }" /></div><strong>{{ item.completeness }}%</strong></div></td><td><StatusBadge :value="item.status" /></td><td class="table-muted">{{ item.updatedAt }}</td><td><RouterLink v-if="item.canEdit || item.canReview" class="row-action" :to="`/members/${item.id}/edit`">{{ item.canReview && item.status === '待审核' ? '审核' : '编辑' }}</RouterLink><span v-else class="table-muted">查看</span></td></tr>
      </tbody></table></div>
    </section>
  </div>
</template>
