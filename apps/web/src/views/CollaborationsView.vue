<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { Collaboration, CollaborationActivity, CollaborationUpsertPayload } from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime, nullableText, splitItems } from './business-form'

const route = useRoute()
const auth = useAuth()
const items = ref<Collaboration[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const tab = ref('进行中')
const createOpen = ref(false)
const selected = ref<Collaboration | null>(null)
const activities = ref<CollaborationActivity[]>([])
const activityDetail = ref('')
const transitionDetail = ref('')
const form = reactive({ title: '', participants: '', owner: '', priority: '中', nextAction: '', dueDate: '', progress: '0', matchId: '' })
const canCreate = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || ''))
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const canManage = (item: Collaboration) => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || '') || (auth.user.value?.role === 'ENTERPRISE_ADMIN' && auth.user.value.enterpriseId === item.enterpriseId)
const filtered = computed(() => items.value.filter((item) => tab.value === '全部' || (tab.value === '已完成' ? item.stage === 'COMPLETED' : !['COMPLETED', 'DISABLED'].includes(item.stage))))

async function load() {
  loading.value = true; error.value = null
  try {
    const result = await platformApi.collaborations('', page.value, size.value)
    items.value = result.items; total.value = result.total; page.value = result.page; size.value = result.size
    if (!result.items.length && result.total > 0 && page.value > 0) { page.value -= 1; await load() }
  }
  catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, { title: '', participants: '', owner: '', priority: '中', nextAction: '', dueDate: '', progress: '0', matchId: typeof route.query.match === 'string' ? route.query.match : '' })
  createOpen.value = true
}

function payload(): CollaborationUpsertPayload {
  return { title: form.title.trim(), participants: splitItems(form.participants), owner: nullableText(form.owner), priority: nullableText(form.priority), nextAction: nullableText(form.nextAction), dueDate: form.dueDate || null, progress: Math.min(100, Math.max(0, Number(form.progress) || 0)), matchId: nullableText(form.matchId) }
}

async function create() {
  if (busy.value) return
  busy.value = true; message.value = ''
  try { await platformApi.createCollaboration(payload()); page.value = 0; await load(); createOpen.value = false; message.value = '协作草稿已建立，请核对参与方和下一步后提交审核。' }
  catch (reason) { message.value = apiActionMessage(reason, '协作创建失败，请检查标题和关联匹配。') }
  finally { busy.value = false }
}

async function openDetail(item: Collaboration) {
  selected.value = item; activities.value = []; activityDetail.value = ''; transitionDetail.value = ''; busy.value = true
  try { activities.value = await platformApi.collaborationActivities(item.id) }
  catch (reason) { message.value = apiActionMessage(reason, '协作动态加载失败。') }
  finally { busy.value = false }
}

function replace(saved: Collaboration) { items.value = items.value.map((item) => item.id === saved.id ? saved : item); selected.value = saved }

async function submit(item: Collaboration) { await act(async () => platformApi.submitCollaboration(item), '协作已提交协会审核。') }
async function review(item: Collaboration, approved: boolean) { await act(async () => platformApi.reviewCollaboration(item, approved, approved ? '' : '请完善参与方与推进计划'), approved ? '协作已审核开放。' : '协作已退回修订。') }
async function transition(item: Collaboration, targetStage: string) { await act(async () => platformApi.transitionCollaboration(item, targetStage, transitionDetail.value), `协作已转入“${displayBusinessStatus(targetStage)}”。`) }

async function act(operation: () => Promise<Collaboration>, success: string) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try { replace(await operation()); message.value = success }
  catch (reason) { message.value = apiActionMessage(reason, '协作状态更新失败。') }
  finally { busy.value = false }
}

async function addActivity() {
  if (!selected.value || !activityDetail.value.trim() || busy.value) return
  busy.value = true
  try { const saved = await platformApi.addCollaborationActivity(selected.value.id, 'PROGRESS_NOTE', activityDetail.value.trim()); activities.value = [saved, ...activities.value]; activityDetail.value = ''; message.value = '进展记录已保存。' }
  catch (reason) { message.value = apiActionMessage(reason, '进展记录保存失败。') }
  finally { busy.value = false }
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }

onMounted(async () => { await load(); if (route.query.create === '1' || typeof route.query.match === 'string') openCreate() })
</script>

<template>
  <div>
    <PageHeader eyebrow="COLLABORATION" title="协作事项" description="从匹配确认、协会审核、多方推进到完成归档的可追踪闭环">
      <button v-if="canCreate" class="primary-button" @click="openCreate">+ 发起协作</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <section class="workflow-strip panel"><div><i>1</i><span><b>建立草稿</b><small>参与方确认</small></span></div><em>→</em><div><i>2</i><span><b>协会审核</b><small>开放协作</small></span></div><em>→</em><div class="active"><i>3</i><span><b>协同推进</b><small>记录每次进展</small></span></div><em>→</em><div><i>4</i><span><b>结果归档</b><small>完成可重开</small></span></div></section>
    <div class="list-toolbar"><div class="segmented"><button v-for="value in ['进行中', '已完成', '全部']" :key="value" :class="{ active: tab === value }" @click="tab = value">{{ value }}</button></div><span>本页 {{ filtered.length }} 个，共 {{ total }} 个事项</span></div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else class="collaboration-list"><article v-for="item in filtered" :key="item.id" class="collaboration-card panel"><div class="collab-id">{{ item.id.slice(0, 4) }}</div><div class="collab-main"><div class="collab-title"><StatusBadge :value="item.priority" /><h2>{{ item.title }}</h2></div><p class="participants"><span v-for="participant in item.participants" :key="participant">{{ participant }}</span><span v-if="!item.participants.length">待确认参与方</span></p><div class="collab-progress"><div><span>当前阶段 · {{ displayBusinessStatus(item.stage) }}</span><strong>{{ item.progress }}%</strong></div><div class="progress-track"><i :style="{ width: `${item.progress}%` }" /></div></div></div><div class="collab-next"><span>下一步行动</span><strong>{{ item.nextAction || '待确定' }}</strong><small>负责人：{{ item.owner || '未指定' }} · 截止 {{ item.dueDate || '未设置' }}</small></div><button class="secondary-button small" @click="openDetail(item)">进入协作</button></article><div v-if="!filtered.length" class="panel empty-business-state"><b>暂无协作事项</b><span>确认的匹配可直接发起协作，也可独立建立协会事项。</span></div><PaginationBar :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" /></section>

    <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false"><form class="panel modal-card" @submit.prevent="create"><div class="modal-head"><div><span class="eyebrow">COLLABORATION DRAFT</span><h2>发起协作</h2></div><button type="button" class="icon-button" @click="createOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>协作标题 *</span><input v-model="form.title" required maxlength="200" /></label><label class="form-span-2"><span>参与方</span><textarea v-model="form.participants" rows="3" /></label><label><span>负责人</span><input v-model="form.owner" /></label><label><span>优先级</span><select v-model="form.priority"><option>高</option><option>中</option><option>低</option></select></label><label class="form-span-2"><span>下一步行动</span><input v-model="form.nextAction" /></label><label><span>截止日期</span><input v-model="form.dueDate" type="date" /></label><label><span>当前进度</span><input v-model="form.progress" type="number" min="0" max="100" /></label><label class="form-span-2"><span>关联匹配 ID</span><input v-model="form.matchId" placeholder="从匹配页进入时会自动填入" /></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="createOpen = false">取消</button><button class="primary-button" :disabled="busy">保存协作草稿</button></div></form></div>
    <div v-if="selected" class="modal-backdrop" @click.self="selected = null"><section class="panel modal-card collaboration-detail"><div class="modal-head"><div><span class="eyebrow">COLLABORATION DETAIL</span><h2>{{ selected.title }}</h2></div><button class="icon-button" @click="selected = null">×</button></div><div class="detail-grid"><div><span>阶段</span><strong>{{ displayBusinessStatus(selected.stage) }}</strong></div><div><span>版本</span><strong>{{ selected.version }}</strong></div><div><span>负责人</span><strong>{{ selected.owner || '—' }}</strong></div><div><span>关联匹配</span><strong>{{ selected.matchId || '—' }}</strong></div></div><div v-if="canManage(selected)" class="transition-panel"><input v-model="transitionDetail" placeholder="填写本次状态变更说明" /><button v-if="['DRAFT', 'REJECTED'].includes(selected.stage)" class="secondary-button small" @click="submit(selected)">提交审核</button><button v-if="selected.stage === 'OPEN'" class="primary-button small" @click="transition(selected, 'IN_PROGRESS')">开始推进</button><button v-if="selected.stage === 'IN_PROGRESS'" class="secondary-button small" @click="transition(selected, 'OPEN')">退回开放</button><button v-if="selected.stage === 'IN_PROGRESS'" class="primary-button small" @click="transition(selected, 'COMPLETED')">完成归档</button><button v-if="selected.stage === 'COMPLETED'" class="secondary-button small" @click="transition(selected, 'OPEN')">重新打开</button></div><div v-if="canReview && selected.stage === 'PENDING_REVIEW'" class="review-actions"><button class="secondary-button" @click="review(selected, false)">退回修订</button><button class="primary-button" @click="review(selected, true)">审核通过</button></div><div class="activity-editor" v-if="canManage(selected)"><textarea v-model="activityDetail" rows="3" placeholder="记录沟通、方案、测试或成果进展" /><button class="primary-button small" :disabled="!activityDetail.trim() || busy" @click="addActivity">添加进展</button></div><div class="activity-timeline"><h3>推进记录</h3><article v-for="activity in activities" :key="activity.id"><span>{{ activity.type }}</span><p>{{ activity.detail }}</p><small>{{ activity.actorSubject }} · {{ formatDateTime(activity.occurredAt) }}</small></article><div v-if="!activities.length" class="empty-business-state"><b>暂无进展记录</b></div></div></section></div>
  </div>
</template>
