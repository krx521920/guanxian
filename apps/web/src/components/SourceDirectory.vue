<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../services/auth'
import { sourceDirectory, sourceLink, tenderDeadlineLabel, type SourceEntry, type SourceKind } from '../services/source-directory'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import AsyncResourceState from './AsyncResourceState.vue'
import PaginationBar from './PaginationBar.vue'

const props = defineProps<{ kind: SourceKind }>()
const auth = useAuth()
const items = ref<SourceEntry[]>([])
const q = ref(''); const page = ref(0); const size = ref(20); const total = ref(0)
const loading = ref(false); const error = ref<PageResourceError | null>(null)
let sequence = 0
const title = computed(() => ({ ENTERPRISE: '企业供给资料', POLICY: '政策来源目录', ASSOCIATION: '相关协会目录', TENDER: '外部招投标' })[props.kind])
const columns = computed(() => ({
  ENTERPRISE: ['主体类型', '业务领域', '主要产品与服务', '能力与资质', '已知协会关系'],
  POLICY: ['文件类型', '发布机构', '发布日期', '实施日期', '核心要求', '全文状态'],
  ASSOCIATION: ['领域', '协会简介', '联系电话', '邮箱', '地址'],
  TENDER: ['采购人或招标人', '项目编号', '项目地区', '采购类型', '发布日期', '截止或开标时间', '预算或最高限价', '采购内容', '关键要求', '需求标签', '核验日期'],
})[props.kind])
const link = (item: SourceEntry) => sourceLink(item.fields['原文链接'] || item.fields['官方原文链接'] || item.fields['官网'] || item.fields['网址'])
async function load() {
  const current = ++sequence
  loading.value = true; error.value = null; items.value = []; total.value = 0
  try {
    const result = await sourceDirectory(props.kind, q.value, page.value, size.value)
    if (sequence !== current) return
    items.value = result.items; total.value = result.total
  } catch (reason) { if (sequence === current) error.value = safePageResourceError(reason) }
  finally { if (sequence === current) loading.value = false }
}
function search() { page.value = 0; void load() }
function changePage(value: number) { page.value = value; void load() }
function resize(value: number) { size.value = value; search() }
watch(() => [props.kind, auth.user.value?.id, auth.user.value?.associationId, auth.user.value?.enterpriseId, auth.user.value?.role], () => {
  q.value = ''; page.value = 0; void load()
}, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>

<template>
  <section class="source-directory" :aria-label="title">
    <div class="panel source-toolbar">
      <div><h2>{{ title }}</h2>
        <p v-if="kind === 'TENDER'">外部公告摘录，不是会员发布的合作需求。时间和资格要求以官方原文及更正公告为准。</p>
        <p v-else-if="kind === 'ASSOCIATION'">资料目录不代表已建立合作关系，也不授予跨协会数据权限。</p>
        <p v-else>导入时的资料快照；当前企业资料及修改入口见企业详情。空缺信息待负责人补充。</p>
      </div>
      <form class="source-search" @submit.prevent="search"><input v-model="q" maxlength="200" :aria-label="`搜索${title}`" placeholder="搜索名称或资料内容" /><button class="secondary-button" type="submit">搜索</button></form>
    </div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else>
      <div class="source-grid">
        <article v-for="item in items" :key="item.id" class="panel source-card">
          <small>{{ item.sourceId }}<template v-if="kind === 'TENDER'"> · {{ tenderDeadlineLabel(item.fields['截止或开标时间']) }}</template></small>
          <h3>{{ item.title }}</h3>
          <dl><template v-for="field in columns" :key="field"><dt>{{ field }}</dt><dd>{{ item.fields[field]?.trim() || '待补充' }}</dd></template></dl>
          <div class="source-actions"><RouterLink v-if="item.enterpriseId" class="text-button" :to="{ path: '/members', query: { memberId: item.enterpriseId } }">企业详情</RouterLink><a v-if="link(item)" class="text-button" :href="link(item)!" target="_blank" rel="noopener noreferrer">查看来源原文 ↗</a></div>
        </article>
      </div>
      <p v-if="!items.length" class="panel source-toolbar">当前范围暂无资料。</p>
      <PaginationBar :page="page" :size="size" :total="total" @change="changePage" @resize="resize" />
    </template>
  </section>
</template>

<style scoped>
.source-directory { margin: 20px 0; min-width: 0; }
.source-toolbar { padding: 20px; display: flex; gap: 20px; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.source-toolbar h2 { margin: 0 0 8px; font-size: 20px; }
.source-toolbar p { margin: 0; color: var(--text-secondary, #526174); line-height: 1.6; }
.source-search { display: flex; gap: 8px; flex-shrink: 0; }
.source-search input { min-width: 0; padding: 10px; border: 1px solid #ccd5df; border-radius: 8px; }
.source-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.source-card { padding: 22px; min-width: 0; }
.source-card h3 { font-size: 18px; line-height: 1.5; margin: 10px 0 18px; overflow-wrap: anywhere; }
.source-card small { color: #526174; }
dl { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 10px 14px; line-height: 1.65; margin: 0; }
dt { color: #526174; } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.source-actions { display: flex; gap: 18px; margin-top: 20px; flex-wrap: wrap; }
@media (max-width: 1000px) { .source-grid { grid-template-columns: 1fr; } .source-toolbar { align-items: stretch; flex-direction: column; } }
@media (max-width: 520px) { dl { grid-template-columns: 1fr; gap: 4px; } dd { margin-bottom: 10px; } .source-search input { width: 100%; } }
</style>
