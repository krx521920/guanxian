<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuth } from '../services/auth'
import { sourceDirectory, sourceLink, sourceLinks, tenderStageLabel, type SourceEntry, type SourceKind } from '../services/source-directory'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import AsyncResourceState from './AsyncResourceState.vue'
import PaginationBar from './PaginationBar.vue'

const props = defineProps<{ kind: SourceKind }>()
const auth = useAuth()
const items = ref<SourceEntry[]>([])
const q = ref(''); const page = ref(0); const size = ref(20); const total = ref(0)
const loading = ref(false); const error = ref<PageResourceError | null>(null)
const now = ref(Date.now())
const clockTimer = setInterval(() => { now.value = Date.now() }, 60_000)
let sequence = 0
const title = computed(() => ({ ENTERPRISE: '企业供给资料', POLICY: '政策来源目录', ASSOCIATION: '相关协会目录', TENDER: '外部招投标', ACTIVITY: '企业活动与公开动态' })[props.kind])
const columns = computed(() => ({
  ENTERPRISE: ['主体类型', '业务领域', '主要产品与服务', '能力与资质', '已知协会关系'],
  POLICY: ['文件类型', '发布机构', '文号或标准号', '发布日期', '实施日期', '版本说明', '日期说明', '核心要求', '全文状态'],
  ASSOCIATION: ['领域', '协会简介', '联系电话', '邮箱', '地址'],
  TENDER: ['采购人或招标人', '项目编号', '项目地区', '采购类型', '发布日期', '截止或开标时间', '预算或最高限价', '采购内容', '关键要求', '需求标签', '核验日期'],
  ACTIVITY: ['发布日期', '活动时间', '核验日期'],
})[props.kind])
const correctionLinks = (item: SourceEntry) => (item.correction?.evidenceUrls || []).map(sourceLink).filter((link): link is string => !!link)
const evidenceLinks = (item: SourceEntry) => (item.evidence?.supportingUrls || []).map(sourceLink).filter((link): link is string => !!link)
const visibleColumns = (item: SourceEntry) => item.evidence ? [
  ...['记录状态', '证据类型', '发布日期', '活动时间', '采购人或招标人', '项目编号', '项目地区', '关联企业及角色',
    '证据摘要', '金额及口径', '分标段信息', '披露份额', '后续结果', '截止或开标时间', '核验日期', '核验边界'].filter(key => item.fields[key]),
] : props.kind !== 'TENDER' ? columns.value : [
  ...['来源平台', '公告类型'].filter(key => item.fields[key]), ...columns.value,
  ...['预计公告日期', '文件获取开始时间', '文件获取截止时间', '提交截止类型', '业务关联说明', '核验边界'].filter(key => item.fields[key]),
]
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
onBeforeUnmount(() => { sequence++; clearInterval(clockTimer) })
</script>

<template>
  <section class="source-directory" :aria-label="title">
    <div class="panel source-toolbar">
      <div><h2>{{ title }}</h2>
        <p v-if="kind === 'TENDER'">外部招采线索与历史记录，不是会员发布的合作需求。候选不等于中标，历史公告不代表仍可报名；资格、标段范围和更正情况以官方文件为准。</p>
        <p v-else-if="kind === 'ACTIVITY'">来源于公开报道，不是平台确认的合作。计划、已举办和企业自述分别标注；未核实的线索不展示。</p>
        <p v-else-if="kind === 'ASSOCIATION'">资料目录不代表已建立合作关系，也不授予跨协会数据权限。</p>
        <p v-else-if="kind === 'POLICY'">来源资料与已记录的元数据更正；日期和编号更正不等于现行效力或企业适用性已核验。</p>
        <p v-else>导入时的资料快照；当前企业资料及修改入口见企业详情。空缺信息待负责人补充。</p>
      </div>
      <form class="source-search" @submit.prevent="search"><input v-model="q" maxlength="200" :aria-label="`搜索${title}`" placeholder="搜索名称或资料内容" /><button class="secondary-button" type="submit">搜索</button></form>
    </div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else>
      <div class="source-grid">
        <article v-for="item in items" :key="item.id" class="panel source-card">
          <small>{{ item.sourceId }}<template v-if="kind === 'TENDER'"> · {{ tenderStageLabel(item.fields, now) }}</template><template v-else-if="kind === 'ACTIVITY'"> · {{ item.fields['记录状态'] || '状态待核实' }}</template></small>
          <h3>{{ item.evidence?.title || item.title }}</h3>
          <p v-if="item.evidence" class="evidence-asof">公开证据 {{ item.evidence.recordId }} · 状态核验截至 {{ item.evidence.checkedOn }}，不代表实时进展</p>
          <dl><template v-for="field in visibleColumns(item)" :key="field"><dt>{{ field === '截止或开标时间' ? item.fields['提交截止类型'] || field : field }}</dt><dd>{{ item.fields[field]?.trim() || '待补充' }}</dd></template></dl>
          <details v-if="item.correction && item.originalFields" class="source-correction">
            <summary>元数据更正记录 · {{ item.correction.checkedOn || '日期待补充' }}</summary>
            <p>{{ item.correction.reason }}</p>
            <p>原始资料保留如下；空缺或未经核实的内容不代表已通过。</p>
            <dl><template v-for="(value, field) in item.originalFields" :key="field"><dt>{{ field }}</dt><dd>{{ value?.trim() || '原资料未填' }}</dd></template></dl>
            <div class="source-actions"><a v-for="(url, index) in correctionLinks(item)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">更正依据 {{ index + 1 }} ↗</a></div>
          </details>
          <details v-if="item.evidence && item.evidence.recordId !== item.sourceId && item.originalFields" class="source-correction">
            <summary>查看保留的原始项目资料</summary><p>{{ item.title }}</p>
            <dl><template v-for="(value, field) in item.originalFields" :key="field"><dt>{{ field }}</dt><dd>{{ value?.trim() || '原资料未填' }}</dd></template></dl>
          </details>
          <div class="source-actions"><RouterLink v-if="item.enterpriseId" class="text-button" :to="{ path: '/members', query: { memberId: item.enterpriseId } }">企业详情</RouterLink><a v-for="link in sourceLinks(item.fields)" :key="link.url" class="text-button" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }}</a></div>
          <div v-if="evidenceLinks(item).length" class="source-actions"><a v-for="(url, index) in evidenceLinks(item)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">补充证据 {{ index + 1 }} ↗</a></div>
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
.evidence-asof { color: #526174; font-size: 13px; line-height: 1.65; margin: -8px 0 16px; }
dl { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 10px 14px; line-height: 1.65; margin: 0; }
dt { color: #526174; } dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.source-actions { display: flex; gap: 18px; margin-top: 20px; flex-wrap: wrap; }
.source-correction { margin-top: 18px; padding: 14px; border: 1px solid #ccd5df; border-radius: 8px; font-size: 14px; }
.source-correction summary { cursor: pointer; font-weight: 600; }
.source-correction p { line-height: 1.6; }
@media (max-width: 1000px) { .source-grid { grid-template-columns: 1fr; } .source-toolbar { align-items: stretch; flex-direction: column; } }
@media (max-width: 520px) { dl { grid-template-columns: 1fr; gap: 4px; } dd { margin-bottom: 10px; } .source-search input { width: 100%; } }
</style>
