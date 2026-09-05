<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import { invitationApi, invitationStatus, type EnterpriseInvitation } from '../services/enterprise-onboarding'
import type { MemberEnterprise } from '../types/domain'

const auth = useAuth()
const scopeReady = computed(() => Boolean(auth.user.value?.associationId))
const canReview = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN')
const busy = ref(false), error = ref(''), message = ref(''), query = ref(''), username = ref(''), link = ref('')
const enterprises = ref<MemberEnterprise[]>([]), selected = ref<MemberEnterprise | null>(null)
const items = ref<EnterpriseInvitation[]>([]), page = ref(0), total = ref(0)
const reviewItem = ref<EnterpriseInvitation | null>(null), note = ref(''), verified = ref(false)
const lastPage = computed(() => (page.value + 1) * 20 >= total.value)
const reviewPanel = ref<HTMLElement | null>(null)
async function openReview(item: EnterpriseInvitation) {
  reviewItem.value = item; note.value = ''; verified.value = false
  await nextTick()
  reviewPanel.value?.focus()
  reviewPanel.value?.scrollIntoView({ block: 'center' })
}
async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await task() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请重试' }
  finally { busy.value = false }
}
async function load() {
  const data = await invitationApi.list(page.value)
  items.value = data.items; total.value = data.total
}
async function search() {
  await run(async () => {
    const data = await platformApi.members(query.value.trim(), '', 0, 20)
    enterprises.value = data.items.filter(item => !['已停用', '已删除'].includes(item.status))
  })
}
async function create() {
  if (!selected.value || !username.value.trim()) return
  const target = selected.value
  await run(async () => {
    link.value = ''
    const issued = await invitationApi.create(target.id, username.value.trim())
    link.value = `${window.location.origin}/join#invite=${issued.token}`
    message.value = '邀请已创建，链接仅在本次显示。请通过可信渠道单独发送给指定负责人。'
    username.value = ''; selected.value = null; await load()
  })
}
async function copy() {
  try { await navigator.clipboard.writeText(link.value); message.value = '邀请链接已复制，请仅发送给指定负责人。' }
  catch { message.value = '自动复制不可用，请选中下方链接手动复制。' }
}
async function review(decision: 'APPROVE' | 'REJECT') {
  if (!reviewItem.value || !note.value.trim() || decision === 'APPROVE' && !verified.value) return
  const target = reviewItem.value
  await run(async () => {
    await invitationApi.review(target, decision, note.value.trim())
    reviewItem.value = null; note.value = ''; verified.value = false
    message.value = decision === 'APPROVE' ? '负责人已绑定已有企业，可刷新权限进入自己的工作台。' : '申请已退回。'
    await load()
  })
}
async function revoke(item: EnterpriseInvitation) {
  if (!window.confirm(`撤销给 ${item.username} 的「${item.enterpriseName}」邀请？已开通账号需在审计与账号中停用。`)) return
  await run(async () => { await invitationApi.revoke(item); await load() })
}
async function turnPage(offset: number) { await run(async () => { page.value += offset; await load() }) }
onMounted(() => { if (scopeReady.value && !auth.isDemoMode) void run(load) })
</script>
<template>
  <div class="invitations-page">
    <PageHeader title="企业负责人邀请" description="连接已有企业与负责人账号；不新建企业，不分发密码。"><RouterLink class="secondary-button" to="/operations">审计与账号</RouterLink></PageHeader>
    <p v-if="auth.isDemoMode" class="invite-notice">当前是本地演示身份，邀请绑定仅支持统一认证账号，未开放演示写入。</p>
    <p v-else-if="!scopeReady" class="invite-notice">请先在左下角选择要管理的协会，再邀请负责人。</p>
    <template v-else>
      <p class="invite-notice">流程：选择已有企业 → 指定统一账号 → 负责人登录确认 → 系统管理员核验开通。请先确认负责人已有统一认证账号；没有账号需先联系系统管理员开户。协会管理员可以发起和撤销邀请，不能自行审核绑定。</p>
      <p v-if="error" role="alert" class="invite-error">{{ error }} <button class="text-button" :disabled="busy" @click="run(load)">刷新邀请列表</button></p>
      <p v-if="message" role="status" class="invite-notice">{{ message }}</p>
      <section class="panel invite-panel">
        <h2>邀请已有企业负责人</h2>
        <form class="invite-search" @submit.prevent="search"><label>查找已有企业<input v-model="query" placeholder="输入企业名称" maxlength="200" /></label><button class="secondary-button" :disabled="busy">查找企业</button></form>
        <ul v-if="enterprises.length" class="invite-options"><li v-for="item in enterprises" :key="item.id"><button type="button" :aria-pressed="selected?.id === item.id" @click="selected = item">{{ item.name }} <span>{{ selected?.id === item.id ? '已选择' : '选择' }}</span></button></li></ul>
        <form v-if="selected" class="invite-create" @submit.prevent="create">
          <p>目标企业：<strong>{{ selected.name }}</strong></p>
          <label>负责人统一认证账号名<input v-model="username" required maxlength="100" autocomplete="off" placeholder="准确填写登录账号名，不是姓名或密码" /></label>
          <button class="primary-button" :disabled="busy || !username.trim()">创建 72 小时有效邀请</button>
        </form>
        <div v-if="link" class="invite-link"><label>仅本次显示的邀请链接<input :value="link" readonly @focus="($event.target as HTMLInputElement).select()" /></label><button class="secondary-button" @click="copy">复制邀请链接</button><button class="text-button" @click="link = ''">隐藏链接</button></div>
      </section>
      <section class="panel invite-panel">
        <div class="invite-heading"><h2>邀请与待核验申请</h2><button class="text-button" :disabled="busy" @click="run(load)">刷新</button></div>
        <p v-if="busy" role="status">正在处理…</p><p v-else-if="!items.length">当前范围暂无邀请。</p>
        <article v-for="item in items" :key="item.id" class="invite-row">
          <div><h3>{{ item.enterpriseName }}</h3><p>指定账号：{{ item.username }} · {{ invitationStatus[item.status] }}</p><p>有效期至 {{ new Date(item.expiresAt).toLocaleString() }}</p><p v-if="item.reviewNote">核验反馈：{{ item.reviewNote }}</p></div>
          <div class="invite-actions"><button v-if="item.status === 'CLAIMED' && canReview" class="primary-button" :disabled="busy" @click="openReview(item)">核验绑定</button><button v-if="['ISSUED','CLAIMED','EXPIRED'].includes(item.status)" class="secondary-button" :disabled="busy" @click="revoke(item)">撤销邀请</button></div>
        </article>
        <div class="invite-actions"><button class="secondary-button" :disabled="busy || page === 0" @click="turnPage(-1)">上一页</button><span>第 {{ page + 1 }} 页 · 共 {{ total }} 条</span><button class="secondary-button" :disabled="busy || lastPage" @click="turnPage(1)">下一页</button></div>
      </section>
      <section v-if="reviewItem" ref="reviewPanel" tabindex="-1" class="panel invite-panel invite-review" aria-labelledby="invite-review-title">
        <h2 id="invite-review-title">核验负责人并开通权限</h2><p><strong>{{ reviewItem.enterpriseName }}</strong> · {{ reviewItem.username }} · {{ reviewItem.claimantName }}</p>
        <p>已登录的统一身份：<code>{{ reviewItem.claimantSubject }}</code></p>
        <p>请通过协会留存的联系方式核验授权关系，不要仅凭邀请链接或姓名判断。批准后授予本企业维护权限，不授予协会或平台管理权限。</p>
        <label>核验依据 / 退回原因<textarea v-model="note" maxlength="1000" rows="3" required placeholder="记录核验渠道和结论，勿填写证件号码或密码" /></label>
        <label class="invite-check"><input v-model="verified" type="checkbox" />我已通过可信渠道确认此账号是该企业授权负责人。</label>
        <div class="invite-actions"><button class="primary-button" :disabled="busy || !verified || !note.trim()" @click="review('APPROVE')">批准绑定</button><button class="secondary-button" :disabled="busy || !note.trim()" @click="review('REJECT')">退回申请</button><button class="text-button" :disabled="busy" @click="reviewItem = null">取消</button></div>
      </section>
    </template>
  </div>
</template>
<style scoped>
.invite-panel{padding:24px;margin:20px 0}.invite-panel h2{font-size:19px;margin:0 0 18px}.invite-panel h3{margin:0;font-size:16px}.invite-panel p{color:var(--muted);line-height:1.8}.invite-panel label{display:grid;gap:8px;font-size:14px}.invite-panel input:not([type=checkbox]),.invite-panel textarea{width:100%;border:1px solid var(--line);background:var(--panel);color:var(--ink);border-radius:8px;padding:12px;font:inherit}.invite-search{display:flex;align-items:end;gap:12px}.invite-search label{flex:1}.invite-options{list-style:none;padding:0;max-height:260px;overflow:auto}.invite-options button{display:flex;width:100%;justify-content:space-between;gap:16px;text-align:left;padding:14px;background:var(--panel);color:var(--ink);border:1px solid var(--line);cursor:pointer}.invite-options button[aria-pressed=true]{background:#e4f1ee}.invite-create,.invite-link{display:grid;gap:16px;max-width:720px;margin-top:24px}.invite-heading,.invite-row{display:flex;justify-content:space-between;gap:24px}.invite-row{border-top:1px solid var(--line);padding:20px 0}.invite-actions{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.invite-notice{padding:18px;background:#edf6f4;border-radius:10px;line-height:1.8}.invite-error{padding:18px;background:#fff1ee;color:#943d2c;border-radius:10px}.invite-review{border:2px solid var(--primary,#28726b)}.invite-check{display:flex!important;align-items:center;margin:20px 0}.invite-panel button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:680px){.invite-row,.invite-search{flex-direction:column;align-items:stretch}.invite-panel{padding:18px}.invite-panel code{overflow-wrap:anywhere}}
</style>
