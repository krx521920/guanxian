<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { MemberEnterprise, Tender, TenderPush, TenderUpsertPayload } from '../types/domain'
import { apiActionMessage, formatDateTime } from './business-form'
const auth = useAuth()
const isAssociation = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(auth.user.value?.role || ''))
const isEnterprise = computed(() => !isAssociation.value)

const items = ref<Tender[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const keyword = ref('')
const category = ref('')
const region = ref('')
const status = ref('')
const mineMode = ref(true)

const categories = ref<string[]>([])
const regions = ref<string[]>([])
const activeCount = ref(0)
const closedCount = ref(0)
let searchTimer: number | null = null

const selected = ref<Tender | null>(null)          // 详情
const createOpen = ref(false)
const pushOpen = ref(false)
const pushRecords = ref<TenderPush[]>([])
const recordsOpen = ref(false)
const recordsBusy = ref(false)
const members = ref<MemberEnterprise[]>([])
const memberKeyword = ref('')
const pushScope = ref<'ALL' | 'PICK'>('ALL')
const pushSelected = ref<string[]>([])
const busy = ref(false)

const statusText = (item: Tender) => (item.status === 'ACTIVE' ? '正在招标' : '已截止')
const isClosingSoon = (item: Tender) => item.status === 'ACTIVE' && item.deadline && new Date(`${item.deadline}T23:59:59`).getTime() - Date.now() < 7 * 86_400_000
const formatMoney = (value: number | null | undefined) => (value == null ? '—' : `${new Intl.NumberFormat('zh-CN').format(Math.round(value / 10000))} 万元`)

const filteredMembers = computed(() => {
  const kw = memberKeyword.value.trim()
  const list = kw ? members.value.filter((m) => `${m.name}${m.role}${m.scenes.join('')}`.includes(kw)) : members.value
  return list.slice(0, 12)
})

const form = reactive({
  title: '', purchaser: '', agency: '', region: '昌平区', category: '热力管网',
  keywords: '', budget: '', publishDate: '', deadline: '', source: '北京市公共资源交易服务平台',
})

async function loadOptions() {
  try {
    const all = await platformApi.tenders('', '', '', '', 0, 200)
    categories.value = [...new Set(all.items.map((t) => t.category).filter(Boolean))].sort()
    regions.value = [...new Set(all.items.map((t) => t.region).filter(Boolean))].sort()
    activeCount.value = all.items.filter((t) => t.status === 'ACTIVE').length
    closedCount.value = all.items.length - activeCount.value
  } catch {
    // 选项加载失败不影响列表
  }
}

async function loadMembers() {
  try {
    const result = await platformApi.members('', 'ACTIVE', 0, 200, false)
    members.value = result.items
  } catch {
    members.value = []
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    let result
    if (isEnterprise.value && mineMode.value) {
      result = await platformApi.tendersMine(page.value, size.value)
    } else {
      result = await platformApi.tenders(keyword.value.trim(), category.value, region.value, status.value, page.value, size.value)
    }
    items.value = result.items
    total.value = result.total
    page.value = result.page
    size.value = result.size
    if (!result.items.length && result.total > 0 && page.value > 0) {
      page.value -= 1
      await load()
    }
  } catch (reason) {
    error.value = safePageResourceError(reason)
  } finally {
    loading.value = false
  }
}

function changePage(value: number) { page.value = value; void load() }
function resizePage(value: number) { size.value = value; page.value = 0; void load() }
function switchMode(mine: boolean) { mineMode.value = mine; page.value = 0; message.value = ''; void load() }

watch([keyword, category, region, status], () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 0
    void load()
  }, 300)
})
onBeforeUnmount(() => { if (searchTimer !== null) window.clearTimeout(searchTimer) })

function openDetail(item: Tender) {
  selected.value = { ...item }
}
function closeDetail() { selected.value = null }

function openCreate() {
  Object.assign(form, {
    title: '', purchaser: '', agency: '', region: '昌平区', category: '热力管网',
    keywords: '', budget: '', publishDate: new Date().toISOString().slice(0, 10),
    deadline: '', source: '北京市公共资源交易服务平台',
  })
  createOpen.value = true
}

function tenderPayload(): TenderUpsertPayload {
  return {
    title: form.title.trim(),
    purchaser: form.purchaser.trim(),
    agency: form.agency.trim() || null,
    region: form.region,
    category: form.category,
    keywords: form.keywords.split(/[\n,，、;；]+/).map((s) => s.trim()).filter(Boolean).slice(0, 10),
    budget: Number(form.budget) || 0,
    publishDate: form.publishDate || new Date().toISOString().slice(0, 10),
    deadline: form.deadline || null,
    source: form.source.trim() || '北京市公共资源交易服务平台',
    sourceUrl: null,
    status: 'ACTIVE',
  }
}

async function saveTender() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try {
    await platformApi.createTender(tenderPayload())
    createOpen.value = false
    page.value = 0
    await loadOptions()
    await load()
    message.value = '招标公告已收录。正式使用时可从采招网批量采集后一次性导入。'
  } catch (reason) {
    message.value = apiActionMessage(reason, '收录失败，请检查必填项。')
  } finally {
    busy.value = false
  }
}

function openPush(item: Tender) {
  if (busy.value) return
  pushScope.value = 'ALL'
  pushSelected.value = []
  memberKeyword.value = ''
  message.value = ''
  selected.value = { ...item }
  pushOpen.value = true
  if (!members.value.length) void loadMembers()
}

async function submitPush() {
  if (busy.value) return
  busy.value = true
  message.value = ''
  const tender = selected.value
  if (!tender) return
  try {
    const enterpriseIds = pushScope.value === 'ALL' ? null : pushSelected.value
    if (enterpriseIds !== null && !enterpriseIds.length) {
      message.value = '请先选择要推送的会员企业，或切换为“推送给全部会员”。'
      return
    }
    const pushed = await platformApi.pushTender(tender.id, enterpriseIds)
    pushOpen.value = false
    await load()
    message.value = `已向 ${pushed.length} 家会员企业推送「${tender.title}」，企业端将收到通知。`
  } catch (reason) {
    message.value = apiActionMessage(reason, '推送失败，请稍后重试。')
  } finally {
    busy.value = false
  }
}

async function openRecords(item: Tender) {
  recordsBusy.value = true
  error.value = null
  try {
    pushRecords.value = await platformApi.tenderPushes(item.id)
    recordsOpen.value = true
  } catch (reason) {
    message.value = apiActionMessage(reason, '推送记录加载失败。')
  } finally {
    recordsBusy.value = false
  }
}

onMounted(() => {
  void load()
  void loadOptions()
})
</script>

<template>
  <div>
    <PageHeader eyebrow="TENDER DESK" title="招标信息" :description="isAssociation ? '汇集行业招标公告，由协会甄选后主动推送相关会员企业，形成供需对接抓手' : '系统已按企业能力与场景自动匹配招标信息，协会推送内容优先展示'">
      <button v-if="isAssociation" class="secondary-button" type="button" @click="openCreate">+ 收录招标公告</button>
      <button v-else class="secondary-button" type="button" @click="switchMode(mineMode ? false : true)">{{ mineMode ? '浏览全部招标' : '回到与我相关' }}</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>

    <section class="panel filter-panel" v-if="isAssociation">
      <div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索招标项目、采购人或关键词" /></div>
      <select v-model="category" class="filter-select"><option value="">全部类别</option><option v-for="c in categories" :key="c" :value="c">{{ c }}</option></select>
      <select v-model="region" class="filter-select"><option value="">全部区域</option><option v-for="r in regions" :key="r" :value="r">{{ r }}</option></select>
      <select v-model="status" class="filter-select"><option value="">全部状态</option><option value="ACTIVE">正在招标</option><option value="CLOSED">已截止</option></select>
      <span class="result-count">正在招标 {{ activeCount }} 条 · 已截止 {{ closedCount }} 条</span>
    </section>
    <section class="panel filter-panel" v-else>
      <div class="segmented">
        <button :class="{ active: mineMode }" @click="switchMode(true)">与我相关</button>
        <button :class="{ active: !mineMode }" @click="switchMode(false)">全部招标</button>
      </div>
      <div v-if="!mineMode" class="search-box compact"><span>⌕</span><input v-model="keyword" placeholder="搜索招标项目、采购人或关键词" /></div>
      <span class="result-count">系统按企业产品、能力与场景自动匹配</span>
    </section>

    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else>
      <section class="panel">
        <div class="data-table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>招标项目</th>
                <th v-if="isAssociation">类别</th>
                <th v-if="isAssociation">区域</th>
                <th>预算</th>
                <th>发布日期</th>
                <th>截止日期</th>
                <th>状态</th>
                <th v-if="isEnterprise">协会推送</th>
                <th v-if="isAssociation">推送记录</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.id">
                <td>
                  <div>
                    <strong>{{ item.title }}</strong>
                    <div class="table-muted" style="font-size: 10px; margin-top: 3px;">{{ item.purchaser }} · {{ item.source }}</div>
                    <div class="tags" style="margin-top: 5px;">
                      <span v-for="k in item.keywords.slice(0, 3)" :key="k">{{ k }}</span>
                      <span v-if="item.relevance" class="score-note">匹配 {{ item.relevance }} 项</span>
                    </div>
                  </div>
                </td>
                <td v-if="isAssociation">{{ item.category }}</td>
                <td v-if="isAssociation">{{ item.region }}</td>
                <td><strong>{{ formatMoney(item.budget) }}</strong></td>
                <td class="table-muted">{{ item.publishDate }}</td>
                <td>
                  <span :class="{ 'deadline-near': isClosingSoon(item) }">{{ item.deadline || '—' }}</span>
                </td>
                <td><StatusBadge :value="statusText(item)" /></td>
                <td v-if="isEnterprise">
                  <span v-if="item.pushedToMe" class="pushed-chip">协会已推送</span>
                  <span v-else class="table-muted">—</span>
                </td>
                <td v-if="isAssociation">
                  <button class="text-button" :disabled="recordsBusy" @click="openRecords(item)">{{ item.pushCount ?? 0 }} 家 →</button>
                </td>
                <td>
                  <button class="row-action" type="button" @click="openDetail(item)">查看</button>
                  <button v-if="isAssociation && item.status === 'ACTIVE'" class="row-action" type="button" @click="openPush(item)">推送</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!items.length" class="panel empty-business-state">
            <b>{{ mineMode && isEnterprise ? '暂无与您相关的招标信息' : '暂无招标信息' }}</b>
            <span v-if="mineMode && isEnterprise">完善企业产品与服务资料后，匹配会更精准。</span>
            <span v-else>持续收录招标公告后，协会可主动推送相关会员。</span>
          </div>
        </div>
        <PaginationBar v-if="total > size" :page="page" :size="size" :total="total" :disabled="loading" @change="changePage" @resize="resizePage" />
      </section>
    </template>

    <!-- 详情 -->
    <div v-if="selected && !pushOpen" class="modal-backdrop" @click.self="closeDetail">
      <div class="panel modal-card match-detail-modal">
        <div class="modal-head">
          <div><span class="eyebrow">TENDER DETAIL</span><h2>{{ selected.title }}</h2></div>
          <button type="button" class="icon-button" aria-label="关闭" @click="closeDetail">×</button>
        </div>
        <div class="detail-grid">
          <div><span>采购人</span><strong>{{ selected.purchaser }}</strong></div>
          <div><span>类别</span><strong>{{ selected.category }}</strong></div>
          <div><span>区域</span><strong>{{ selected.region }}</strong></div>
          <div><span>预算</span><strong>{{ formatMoney(selected.budget) }}</strong></div>
          <div><span>发布日期</span><strong>{{ selected.publishDate }}</strong></div>
          <div><span>截止日期</span><strong :class="{ 'deadline-near': isClosingSoon(selected) }">{{ selected.deadline || '—' }}</strong></div>
        </div>
        <div class="modal-copy"><span>信息来源</span><p>{{ selected.source }}<template v-if="selected.agency"> · 代理机构：{{ selected.agency }}</template></p></div>
        <div class="modal-copy"><span>关键词</span><div class="tags"><span v-for="k in selected.keywords" :key="k">{{ k }}</span></div></div>
        <div class="form-actions">
          <button type="button" class="secondary-button" @click="closeDetail">关闭</button>
          <button v-if="isAssociation && selected.status === 'ACTIVE'" type="button" class="primary-button" @click="openPush(selected)">推送给会员</button>
        </div>
      </div>
    </div>

    <!-- 收录（协会） -->
    <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false">
      <form class="panel modal-card" @submit.prevent="saveTender">
        <div class="modal-head"><div><span class="eyebrow">COLLECT TENDER</span><h2>收录招标公告</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="createOpen = false">×</button></div>
        <div class="modal-copy"><p>从采招网、北京市公共资源交易服务平台等渠道采集公告后录入；系统将据此向相关会员推送并生成商机提醒。</p></div>
        <div class="form-grid modal-form">
          <label class="form-span-2"><span>招标项目名称 *</span><input v-model="form.title" required maxlength="200" /></label>
          <label class="form-span-2"><span>采购人 / 招标单位</span><input v-model="form.purchaser" maxlength="120" /></label>
          <label><span>类别</span><select v-model="form.category"><option v-for="c in categories.length ? categories : ['热力管网', '燃气管网', '供水管网', '排水管网', '管线探测', '非开挖修复', '电力管沟', '材料设备']" :key="c" :value="c">{{ c }}</option></select></label>
          <label><span>区域</span><select v-model="form.region"><option v-for="r in regions.length ? regions : ['昌平区', '海淀区', '朝阳区', '丰台区', '通州区', '大兴区', '房山区']" :key="r" :value="r">{{ r }}</option></select></label>
          <label><span>预算（元）</span><input v-model="form.budget" type="number" min="0" step="1000" placeholder="例如 4600000" /></label>
          <label><span>截止日期</span><input v-model="form.deadline" type="date" /></label>
          <label><span>发布日期</span><input v-model="form.publishDate" type="date" /></label>
          <label class="form-span-2"><span>关键词（用于能力匹配，逗号分隔）</span><input v-model="form.keywords" placeholder="例如：管线探测,普查,测绘" /></label>
          <label class="form-span-2"><span>来源</span><input v-model="form.source" /></label>
        </div>
        <div class="form-actions"><button type="button" class="secondary-button" @click="createOpen = false">取消</button><button class="primary-button" :disabled="busy">{{ busy ? '正在收录…' : '收录公告' }}</button></div>
      </form>
    </div>

    <!-- 推送（协会） -->
    <div v-if="pushOpen" class="modal-backdrop" @click.self="pushOpen = false">
      <div class="panel modal-card">
        <div class="modal-head"><div><span class="eyebrow">PUSH TENDER</span><h2>向会员推送招标</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="pushOpen = false">×</button></div>
        <div class="modal-copy"><p><strong>{{ selected?.title }}</strong></p></div>
        <div class="filter-panel" style="margin: 0 0 10px;">
          <label class="push-scope"><input v-model="pushScope" type="radio" value="ALL" /><span>推送给全部会员</span></label>
          <label class="push-scope"><input v-model="pushScope" type="radio" value="PICK" /><span>定向选择会员</span></label>
        </div>
        <template v-if="pushScope === 'PICK'">
          <div class="search-box" style="margin-bottom: 8px;"><span>⌕</span><input v-model="memberKeyword" placeholder="搜索会员企业（按能力相关优先）" /></div>
          <div class="member-pick-list">
            <label v-for="m in filteredMembers" :key="m.id" class="member-pick-item">
              <input v-model="pushSelected" type="checkbox" :value="m.id" />
              <span><strong>{{ m.name }}</strong><small>{{ m.role }} · {{ m.scenes.join('、') }}</small></span>
            </label>
            <div v-if="!filteredMembers.length" class="notification-state"><b>未找到匹配会员</b><span>换个关键词试试。</span></div>
          </div>
        </template>
        <div class="form-actions">
          <span class="table-muted" style="font-size: 10px;">{{ pushScope === 'ALL' ? '将推送给全部在册会员' : `已选择 ${pushSelected.length} 家` }}</span>
          <button type="button" class="secondary-button" @click="pushOpen = false">取消</button>
          <button class="primary-button" :disabled="busy" @click="submitPush">{{ busy ? '正在推送…' : '确认推送' }}</button>
        </div>
      </div>
    </div>

    <!-- 推送记录 -->
    <div v-if="recordsOpen" class="modal-backdrop" @click.self="recordsOpen = false">
      <div class="panel modal-card">
        <div class="modal-head"><div><span class="eyebrow">PUSH RECORDS</span><h2>推送记录</h2></div><button type="button" class="icon-button" aria-label="关闭" @click="recordsOpen = false">×</button></div>
        <div class="workflow-section">
          <div v-for="record in pushRecords" :key="record.id" class="workflow-record">
            <span class="status-badge status-success">已推送</span>
            <div><strong>{{ record.enterpriseName }}</strong><p>{{ formatDateTime(record.pushedAt) }} · 由协会推送</p></div>
          </div>
          <div v-if="!pushRecords.length" class="notification-state"><b>暂无推送记录</b><span>可点击列表中的“推送”按钮，向相关会员推送该招标。</span></div>
        </div>
        <div class="form-actions"><button type="button" class="secondary-button" @click="recordsOpen = false">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.filter-panel .segmented { margin-right: auto; }
.score-note { color: #b07820 !important; background: #fdf3e0 !important; }
.pushed-chip { display: inline-flex; padding: 3px 8px; border-radius: 11px; color: #1d6e63; background: #e2f3ef; font-size: 10px; font-weight: 600; }
.deadline-near { color: #b42318 !important; font-weight: 600; }
.push-scope { display: inline-flex; align-items: center; gap: 6px; color: #395469; font-size: 12px; }
.member-pick-list { max-height: 260px; overflow-y: auto; border: 1px solid #eef1f4; border-radius: 8px; }
.member-pick-item { width: 100%; padding: 9px 12px; display: flex; gap: 9px; align-items: center; border-bottom: 1px solid #f3f5f7; }
.member-pick-item:last-child { border-bottom: 0; }
.member-pick-item span { min-width: 0; display: grid; gap: 2px; }
.member-pick-item strong { color: #2d3a4e; font-size: 12px; }
.member-pick-item small { color: #8a96a5; font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
