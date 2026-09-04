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
  Collaboration,
  CollaborationActivity,
  CollaborationHistory,
  CollaborationUpsertPayload,
} from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText, splitItems } from './business-form'
import { hasAssociationWriteContext } from './business-view-guards'
import { canOperateCollaborationDetail, saveActivityThenRefreshHistory } from './collaboration-view'

type CollaborationTab = 'ACTIVE' | 'COMPLETED' | ''

const route = useRoute()
const auth = useAuth()
const items = ref<Collaboration[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const detailLoading = ref(false)
const detailReady = ref(false)
const error = ref<PageResourceError | null>(null)
const detailError = ref('')
const message = ref('')
const tab = ref<CollaborationTab>('ACTIVE')
const keyword = ref('')
const includeDeleted = ref(false)
const editorOpen = ref(false)
const editing = ref<Collaboration | null>(null)
const selected = ref<Collaboration | null>(null)
const activities = ref<CollaborationActivity[]>([])
const histories = ref<CollaborationHistory[]>([])
const activityDetail = ref('')
const transitionDetail = ref('')
const form = reactive({
  title: '', participants: '', owner: '', priority: 'MEDIUM', nextAction: '',
  dueDate: '', progress: '0', matchId: '',
})
let searchTimer: number | null = null
let loadRevision = 0
let detailRevision = 0

const role = computed(() => auth.user.value?.role || '')
const hasAssociationContext = computed(() => hasAssociationWriteContext(
  role.value, auth.user.value?.associationId,
))
const systemNeedsAssociation = computed(() => role.value === 'SYSTEM_ADMIN' && !hasAssociationContext.value)
const detailActionEnabled = computed(() => canOperateCollaborationDetail(
  detailReady.value, detailLoading.value, detailError.value,
))
const canCreate = computed(() => [
  'SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN',
].includes(role.value) && hasAssociationContext.value)
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(role.value)
  && hasAssociationContext.value)
const canViewDeleted = computed(() => [
  'SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN',
].includes(role.value))
const canManage = (item: Collaboration) => {
  if (!hasAssociationContext.value) return false
  const user = auth.user.value
  if (!user) return false
  if (role.value === 'SYSTEM_ADMIN') {
    if (user.enterpriseId) {
      return Boolean(item.matchId)
        || (user.associationId === item.associationId && user.enterpriseId === item.enterpriseId)
    }
    return user.associationId === item.associationId
  }
  if (['ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(role.value)) {
    return user.associationId === item.associationId
  }
  return role.value === 'ENTERPRISE_ADMIN'
    && (user.enterpriseId === item.enterpriseId || Boolean(item.matchId))
}
const canReviewItem = (item: Collaboration) => canReview.value
  && auth.user.value?.associationId === item.associationId
const canEdit = (item: Collaboration) => !item.deleted && !item.disabled
  && ['DRAFT', 'REJECTED', 'OPEN', 'IN_PROGRESS'].includes(item.stage) && canManage(item)
const activeMaintenance = computed(() => Boolean(editing.value
  && ['OPEN', 'IN_PROGRESS'].includes(editing.value.stage)))

async function load() {
  const revision = ++loadRevision
  loading.value = true
  error.value = null
  try {
    const result = await platformApi.collaborations(
      keyword.value.trim(), page.value, size.value, includeDeleted.value, tab.value,
    )
    if (revision !== loadRevision) return
    items.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size
    if (!result.items.length && result.total > 0 && page.value > 0) {
      page.value = Math.max(0, Math.ceil(result.total / result.size) - 1)
      await load()
    }
  } catch (reason) {
    if (revision === loadRevision) error.value = safePageResourceError(reason)
  } finally {
    if (revision === loadRevision) loading.value = false
  }
}

function resetForm(item?: Collaboration) {
  Object.assign(form, {
    title: item?.title || '',
    participants: item?.participants.join('\n') || '',
    owner: item?.owner || '',
    priority: item?.priority || 'MEDIUM',
    nextAction: item?.nextAction || '',
    dueDate: item?.dueDate || '',
    progress: String(item?.progress ?? 0),
    matchId: item?.matchId || (typeof route.query.match === 'string' ? route.query.match : ''),
  })
}

function openCreate() {
  if (!hasAssociationContext.value) {
    message.value = '请先选择协会上下文，再发起协作。'
    return
  }
  editing.value = null
  resetForm()
  editorOpen.value = true
}

function openEdit(item: Collaboration) {
  if (!hasAssociationContext.value) {
    message.value = '请先选择协会上下文，再维护协作。'
    return
  }
  editing.value = item
  resetForm(item)
  editorOpen.value = true
}

function payload(): CollaborationUpsertPayload {
  return {
    title: form.title.trim(),
    participants: splitItems(form.participants),
    owner: nullableText(form.owner),
    priority: nullableText(form.priority),
    nextAction: nullableText(form.nextAction),
    dueDate: form.dueDate || null,
    progress: Math.min(activeMaintenance.value ? 99 : 100,
      Math.max(0, Number(form.progress) || 0)),
    matchId: nullableText(form.matchId),
  }
}

async function save() {
  if (busy.value || !hasAssociationContext.value) {
    if (!hasAssociationContext.value) message.value = '请先选择协会上下文，再保存协作。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const wasEditing = Boolean(editing.value)
    const wasMaintaining = activeMaintenance.value
    const saved = editing.value
      ? await platformApi.updateCollaboration(editing.value, payload())
      : await platformApi.createCollaboration(payload())
    editorOpen.value = false
    editing.value = null
    page.value = 0
    await load()
    await openDetail(saved)
    message.value = wasMaintaining
      ? '协作推进信息已更新，历史版本已经留存。'
      : wasEditing
      ? '协作草稿已更新，历史版本已经留存。'
      : '协作草稿已建立，请核对参与方和下一步后提交审核。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '协作保存失败，请重新加载后核对表单。')
  } finally {
    busy.value = false
  }
}

async function openDetail(item: Collaboration) {
  const revision = ++detailRevision
  selected.value = item
  activities.value = []
  histories.value = []
  activityDetail.value = ''
  transitionDetail.value = ''
  detailError.value = ''
  detailReady.value = false
  detailLoading.value = true
  try {
    const [latest, activityValues, historyValues] = await Promise.all([
      platformApi.collaboration(item.id, Boolean(item.deleted)),
      platformApi.collaborationActivities(item.id),
      platformApi.collaborationHistory(item.id),
    ])
    if (revision !== detailRevision) return
    selected.value = latest
    activities.value = activityValues
    histories.value = historyValues
    detailReady.value = true
  } catch (reason) {
    if (revision === detailRevision) {
      detailError.value = apiActionMessage(reason, '协作详情、进展或历史加载失败。')
    }
  } finally {
    if (revision === detailRevision) detailLoading.value = false
  }
}

function closeDetail() {
  detailRevision += 1
  selected.value = null
  detailLoading.value = false
  detailReady.value = false
  detailError.value = ''
}

function replace(saved: Collaboration) {
  items.value = items.value.map((item) => item.id === saved.id ? saved : item)
  selected.value = saved
}

async function act(operation: () => Promise<Collaboration>, success: string, refresh = false) {
  if (busy.value || !hasAssociationContext.value) {
    if (!hasAssociationContext.value) message.value = '请先选择协会上下文，再更新协作。'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const saved = await operation()
    replace(saved)
    message.value = success
    if (refresh) await load()
    if (selected.value) await openDetail(saved)
  } catch (reason) {
    message.value = apiActionMessage(reason, '协作状态更新失败，请重新加载后再试。')
  } finally {
    busy.value = false
  }
}

async function submit(item: Collaboration) {
  await act(() => platformApi.submitCollaboration(item), '协作已提交协会审核。', true)
}

async function review(item: Collaboration, approved: boolean) {
  await act(
    () => platformApi.reviewCollaboration(item, approved, approved ? '' : '请完善参与方与推进计划'),
    approved ? '协作已审核开放。' : '协作已退回修订。',
    true,
  )
}

async function transition(item: Collaboration, targetStage: string) {
  await act(
    () => platformApi.transitionCollaboration(item, targetStage, transitionDetail.value),
    `协作已转入“${displayBusinessStatus(targetStage)}”。`,
    true,
  )
}

async function disable(item: Collaboration) {
  if (!window.confirm(`确认停用协作“${item.title}”？停用后可恢复为草稿。`)) return
  await act(() => platformApi.disableCollaboration(item), '协作已停用，可在管理视图中恢复。', true)
}

async function remove(item: Collaboration) {
  if (!window.confirm(`确认删除协作“${item.title}”？该操作为软删除，可恢复。`)) return
  await act(() => platformApi.deleteCollaboration(item), '协作已软删除，可在“包含已删除”中恢复。', true)
}

async function restore(item: Collaboration) {
  await act(() => platformApi.restoreCollaboration(item), '协作已恢复为草稿，请重新核对后提交。', true)
}

async function addActivity() {
  if (!selected.value || !detailActionEnabled.value
    || !hasAssociationContext.value || !activityDetail.value.trim() || busy.value) return
  const collaborationId = selected.value.id
  const detail = activityDetail.value.trim()
  busy.value = true
  message.value = ''
  try {
    const result = await saveActivityThenRefreshHistory(
      () => platformApi.addCollaborationActivity(collaborationId, 'PROGRESS_NOTE', detail),
      () => platformApi.collaborationHistory(collaborationId),
    )
    activities.value = [result.activity, ...activities.value]
    activityDetail.value = ''
    if (result.histories) histories.value = result.histories
    message.value = result.historyRefreshFailed
      ? '进展记录已保存，但版本历史暂时刷新失败；可重新加载详情重试。'
      : '进展记录已保存并写入历史。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '进展记录保存失败。')
  } finally {
    busy.value = false
  }
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }
function selectTab(value: CollaborationTab) { tab.value = value; page.value = 0; void load() }

watch(keyword, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { page.value = 0; void load() }, 300)
})
watch(includeDeleted, () => { page.value = 0; void load() })

onBeforeUnmount(() => {
  loadRevision += 1
  detailRevision += 1
  if (searchTimer !== null) window.clearTimeout(searchTimer)
})

onMounted(async () => {
  await load()
  if (route.query.create === '1' || typeof route.query.match === 'string') openCreate()
  const collaborationId = typeof route.query.collaborationId === 'string'
    ? route.query.collaborationId : null
  if (collaborationId) {
    try { await openDetail(await platformApi.collaboration(collaborationId, canViewDeleted.value)) }
    catch (reason) { message.value = apiActionMessage(reason, '指定协作不存在或当前身份无权查看。') }
  }
})
</script>

<template>
  <div>
    <PageHeader eyebrow="COLLABORATION" title="协作事项" description="从建立草稿、协会审核、多方推进到完成归档的可追踪闭环">
      <button v-if="canCreate" class="primary-button" type="button" @click="openCreate">+ 发起协作</button>
    </PageHeader>
    <div v-if="systemNeedsAssociation" class="save-message page-message" role="status">
      当前为全平台只读视图。请先在顶部选择协会，再发起、审核或推进协作。
    </div>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="workflow-strip panel" aria-label="协作流程概览">
      <div><i>1</i><span><b>建立草稿</b><small>参与方确认</small></span></div><em>→</em>
      <div><i>2</i><span><b>协会审核</b><small>开放协作</small></span></div><em>→</em>
      <div class="active"><i>3</i><span><b>协同推进</b><small>记录每次进展</small></span></div><em>→</em>
      <div><i>4</i><span><b>结果归档</b><small>完成可重开</small></span></div>
    </section>
    <section class="panel filter-panel">
      <div class="segmented">
        <button type="button" :class="{ active: tab === 'ACTIVE' }" @click="selectTab('ACTIVE')">进行中</button>
        <button type="button" :class="{ active: tab === 'COMPLETED' }" @click="selectTab('COMPLETED')">已完成</button>
        <button type="button" :class="{ active: tab === '' }" @click="selectTab('')">全部</button>
      </div>
      <div class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索标题、参与方或负责人" /></div>
      <label v-if="canViewDeleted" class="checkbox-field"><input v-model="includeDeleted" type="checkbox" /><span>包含已删除</span></label>
      <span>共 {{ total }} 个事项</span>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="collaboration-list">
      <article v-for="item in items" :key="item.id" class="collaboration-card panel">
        <div class="collab-id">{{ item.id.slice(0, 4) }}</div>
        <div class="collab-main">
          <div class="collab-title"><StatusBadge :value="displayBusinessStatus(item.priority)" /><h2>{{ item.title }}</h2></div>
          <p class="participants"><span v-for="participant in item.participants" :key="participant">{{ participant }}</span><span v-if="!item.participants.length">待确认参与方</span></p>
          <div class="collab-progress"><div><span>当前阶段 · {{ item.deleted ? '已删除' : displayBusinessStatus(item.stage) }}</span><strong>{{ item.progress }}%</strong></div><div class="progress-track"><i :style="{ width: `${item.progress}%` }" /></div></div>
        </div>
        <div class="collab-next"><span>下一步行动</span><strong>{{ item.nextAction || '待确定' }}</strong><small>负责人：{{ item.owner || '未指定' }} · 截止 {{ item.dueDate || '未设置' }}</small></div>
        <button class="secondary-button small" type="button" @click="openDetail(item)">进入协作</button>
      </article>
      <div v-if="!items.length" class="panel empty-business-state"><b>暂无符合条件的协作事项</b><span>确认的匹配可直接发起协作，也可独立建立协会事项。</span></div>
      <PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
    </section>

    <div v-if="editorOpen" class="modal-backdrop" role="dialog" aria-modal="true" :aria-label="activeMaintenance ? '维护协作推进信息' : editing ? '编辑协作草稿' : '发起协作'" @click.self="editorOpen = false">
      <form class="panel modal-card" @submit.prevent="save">
        <div class="modal-head"><div><span class="eyebrow">COLLABORATION DRAFT</span><h2>{{ activeMaintenance ? '维护协作推进信息' : editing ? '编辑协作草稿' : '发起协作' }}</h2></div><button type="button" class="icon-button" @click="editorOpen = false">×</button></div>
        <div class="form-grid modal-form">
          <label class="form-span-2"><span>协作标题 *</span><input v-model="form.title" required maxlength="200" :disabled="activeMaintenance" /></label>
          <label class="form-span-2"><span>参与方</span><textarea v-model="form.participants" rows="3" :disabled="activeMaintenance" /></label>
          <label><span>负责人</span><input v-model="form.owner" maxlength="200" /></label>
          <label><span>优先级</span><select v-model="form.priority" :disabled="activeMaintenance"><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
          <label class="form-span-2"><span>下一步行动</span><input v-model="form.nextAction" maxlength="500" /></label>
          <label><span>截止日期</span><input v-model="form.dueDate" type="date" /></label>
          <label><span>当前进度</span><input v-model="form.progress" type="number" min="0" :max="activeMaintenance ? 99 : 100" /></label>
          <label class="form-span-2"><span>关联匹配 ID</span><input v-model="form.matchId" :disabled="activeMaintenance" placeholder="从匹配页进入时会自动填入" /></label>
        </div>
        <div class="form-actions"><button type="button" class="secondary-button" @click="editorOpen = false">取消</button><button v-if="hasAssociationContext" class="primary-button" :disabled="busy">{{ busy ? '正在保存…' : activeMaintenance ? '保存推进信息' : '保存草稿' }}</button></div>
      </form>
    </div>

    <div v-if="selected" class="modal-backdrop" role="dialog" aria-modal="true" :aria-label="`协作详情：${selected.title}`" @click.self="closeDetail">
      <section class="panel modal-card collaboration-detail">
        <div class="modal-head"><div><span class="eyebrow">COLLABORATION DETAIL</span><h2>{{ selected.title }}</h2></div><button class="icon-button" type="button" @click="closeDetail">×</button></div>
        <div v-if="detailError" class="save-message" role="alert">{{ detailError }} <button class="text-button" type="button" @click="openDetail(selected)">重新加载</button></div>
        <div class="detail-grid"><div><span>阶段</span><strong>{{ selected.deleted ? '已删除' : displayBusinessStatus(selected.stage) }}</strong></div><div><span>版本</span><strong>{{ selected.version }}</strong></div><div><span>负责人</span><strong>{{ selected.owner || '—' }}</strong></div><div><span>关联匹配</span><strong>{{ selected.matchId || '—' }}</strong></div></div>
        <div v-if="detailActionEnabled && canManage(selected)" class="transition-panel">
          <input v-model="transitionDetail" :disabled="selected.deleted || selected.disabled" placeholder="填写本次状态变更说明" />
          <button v-if="canEdit(selected)" class="secondary-button small" type="button" :disabled="busy" @click="openEdit(selected)">{{ ['OPEN', 'IN_PROGRESS'].includes(selected.stage) ? '维护推进信息' : '编辑草稿' }}</button>
          <button v-if="['DRAFT', 'REJECTED'].includes(selected.stage)" class="secondary-button small" type="button" :disabled="busy" @click="submit(selected)">提交审核</button>
          <button v-if="!selected.deleted && !selected.disabled && selected.stage === 'OPEN'" class="primary-button small" type="button" :disabled="busy" @click="transition(selected, 'IN_PROGRESS')">开始推进</button>
          <button v-if="!selected.deleted && !selected.disabled && selected.stage === 'IN_PROGRESS'" class="secondary-button small" type="button" :disabled="busy" @click="transition(selected, 'OPEN')">退回开放</button>
          <button v-if="!selected.deleted && !selected.disabled && selected.stage === 'IN_PROGRESS'" class="primary-button small" type="button" :disabled="busy" @click="transition(selected, 'COMPLETED')">完成归档</button>
          <button v-if="!selected.deleted && !selected.disabled && selected.stage === 'COMPLETED'" class="secondary-button small" type="button" :disabled="busy" @click="transition(selected, 'OPEN')">重新打开</button>
          <button v-if="!selected.deleted && !selected.disabled" class="text-button danger-text" type="button" :disabled="busy" @click="disable(selected)">停用</button>
          <button v-if="!selected.deleted && !selected.disabled" class="text-button danger-text" type="button" :disabled="busy" @click="remove(selected)">删除</button>
          <button v-if="selected.deleted || selected.disabled" class="primary-button small" type="button" :disabled="busy" @click="restore(selected)">恢复为草稿</button>
        </div>
        <div v-if="detailActionEnabled && canReviewItem(selected) && !selected.deleted && !selected.disabled && selected.stage === 'PENDING_REVIEW'" class="review-actions"><button class="secondary-button" type="button" :disabled="busy" @click="review(selected, false)">退回修订</button><button class="primary-button" type="button" :disabled="busy" @click="review(selected, true)">审核通过</button></div>
        <div v-if="detailActionEnabled && canManage(selected) && !selected.deleted && !selected.disabled" class="activity-editor"><textarea v-model="activityDetail" rows="3" maxlength="5000" placeholder="记录沟通、方案、测试或成果进展" /><button class="primary-button small" type="button" :disabled="!activityDetail.trim() || busy" @click="addActivity">添加进展</button></div>
        <div class="activity-timeline"><h3>推进记录</h3><p v-if="detailLoading">正在加载…</p><article v-for="activity in activities" :key="activity.id"><span>{{ displayBusinessStatus(activity.type) }}</span><p>{{ activity.detail }}</p><small>{{ activity.actorSubject }} · {{ formatDateTime(activity.occurredAt) }}</small></article><div v-if="!detailLoading && !activities.length" class="empty-business-state"><b>暂无进展记录</b></div></div>
        <div class="activity-timeline"><h3>版本历史</h3><article v-for="history in histories" :key="history.id"><span>{{ displayBusinessStatus(history.action) }} · 版本 {{ history.version }}</span><p>{{ String(history.snapshot.title || selected.title) }}</p><small>{{ history.actorSubject }} · {{ formatDateTime(history.occurredAt) }}</small></article><div v-if="!detailLoading && !histories.length" class="empty-business-state"><b>暂无历史记录</b></div></div>
      </section>
    </div>
  </div>
</template>
