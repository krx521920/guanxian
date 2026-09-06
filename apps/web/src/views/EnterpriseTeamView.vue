<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { useAuth } from '../services/auth'
import { enterpriseTeamApi as api, type TeamMember } from '../services/enterprise-team'
import { invitationStatus, type EnterpriseInvitation } from '../services/enterprise-onboarding'
const auth = useAuth()
const members = ref<TeamMember[]>([]), invitations = ref<EnterpriseInvitation[]>([])
const memberPage = ref(0), invitePage = ref(0), memberTotal = ref(0), inviteTotal = ref(0)
const busy = ref(false), loaded = ref(false), error = ref(''), message = ref(''), username = ref(''), link = ref('')
const selected = ref<TeamMember | null>(null), reason = ref('')
const disableDialog = ref<HTMLDialogElement | null>(null)
watch(selected, async value => {
  if (!value) { disableDialog.value?.close(); return }
  await nextTick()
  if (selected.value) disableDialog.value?.showModal()
})
function cancelDisable(event: Event) {
  if (busy.value) event.preventDefault(); else selected.value = null
}
const memberStatus = { ACTIVE: '已开通 · 只读', INACTIVE: '已停用', NEEDS_REVIEW: '绑定已变化 · 请联系管理员' }
async function load() {
  const [team, pending] = await Promise.all([api.members(memberPage.value), api.invitations(invitePage.value)])
  members.value = team.items; memberTotal.value = team.total
  invitations.value = pending.items; inviteTotal.value = pending.total; loaded.value = true
}
async function run(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await work() } catch (e) { error.value = e instanceof Error ? e.message : '操作失败，请刷新后重试' }
  finally { busy.value = false }
}
async function invite() {
  if (!username.value.trim()) return
  await run(async () => {
    link.value = ''
    const result = await api.invite(username.value)
    link.value = `${window.location.origin}/join#invite=${result.token}`
    username.value = ''; invitePage.value = 0
    message.value = '邀请已创建。请单独发送给指定账号，成员确认且系统管理员核验通过后才会开通。'
    await load()
  })
}
async function copyLink() {
  try { await navigator.clipboard.writeText(link.value); message.value = '已复制邀请链接，请通过可信渠道单独发送。' }
  catch { message.value = '自动复制不可用，请选中下方链接手动复制。' }
}
async function revoke(item: EnterpriseInvitation) {
  if (!window.confirm(`确认撤销给 ${item.username} 的邀请？对方将无法继续使用这个邀请。`)) return
  await run(async () => { await api.revoke(item); link.value = ''; message.value = '邀请已撤销。'; await load() })
}
async function disable() {
  if (!selected.value || !reason.value.trim()) return
  const target = selected.value
  await run(async () => {
    await api.disable(target, reason.value)
    selected.value = null; reason.value = ''; message.value = '成员已停用，后续业务请求将被拒绝；历史业务记录保留。'
    await load()
  })
}
function changePage(kind: 'members' | 'invitations', page: number) {
  if (kind === 'members') memberPage.value = page; else invitePage.value = page
  void run(load)
}
onMounted(() => { if (!auth.isDemoMode) void run(load) })
</script>
<template>
  <div class="team-page">
    <PageHeader title="企业团队" :description="`${auth.user.value?.organization} · 邀请和管理本企业普通成员`"><RouterLink class="secondary-button" to="/enterprise">返回企业工作台</RouterLink></PageHeader>
    <p v-if="auth.isDemoMode" class="team-note">团队邀请需要真实统一认证账号，本地演示身份不开放账号写入。</p>
    <template v-else>
      <p class="team-note">普通成员可查看企业资料、供需和合作进度，不能修改、审核、公开授权或管理团队。企业负责人权限变更仍由系统管理员核验。这里不创建企业、不设置或分发密码。</p>
      <p v-if="error" class="team-error" role="alert">{{ error }} <button class="text-button" :disabled="busy" @click="run(load)">刷新列表</button></p>
      <p v-if="message" class="team-note" role="status">{{ message }}</p>
      <p v-if="busy" role="status">正在处理…</p>
      <section class="panel team-panel">
        <h2>邀请普通成员</h2><p>先请同事向系统管理员申请统一认证账号，再输入准确账号名。邀请有效期 72 小时，仅指定账号可确认。</p>
        <form class="team-invite" @submit.prevent="invite"><label>成员统一认证账号名<input v-model="username" maxlength="100" required autocomplete="off" placeholder="账号名，不是姓名或密码" /></label><button class="primary-button" :disabled="busy || !loaded || !username.trim()">创建成员邀请</button></form>
        <div v-if="link" class="team-link"><label>仅本次显示的成员邀请链接<input :value="link" readonly @focus="($event.target as HTMLInputElement).select()" /></label><div class="team-actions"><button class="secondary-button" @click="copyLink">复制链接</button><button class="text-button" @click="link = ''">隐藏链接</button></div></div>
      </section>
      <section class="panel team-panel">
        <div class="team-heading"><h2>已开通的普通成员</h2><button class="text-button" :disabled="busy" @click="run(load)">刷新团队</button></div>
        <p>仅列出通过邀请流程开通的本企业普通成员，不显示其他企业账号或平台管理员。</p>
        <p v-if="loaded && !members.length && !error">暂无已开通成员。可以先发送邀请，再等待确认与核验。</p>
        <article v-for="item in members" :key="item.id" class="team-row"><div><h3>{{ item.displayName || item.username }}</h3><p>{{ item.username }} · {{ memberStatus[item.status] }}</p></div><button v-if="item.canDisable" class="secondary-button" :disabled="busy" @click="selected = item; reason = ''">停用成员</button></article>
        <PaginationBar :page="memberPage" :size="20" :total="memberTotal" :disabled="busy" fixed-size @change="changePage('members', $event)" />
      </section>
      <section class="panel team-panel">
        <h2>邀请与开通进度</h2><p v-if="loaded && !invitations.length && !error">暂无成员邀请。</p>
        <article v-for="item in invitations" :key="item.id" class="team-row"><div><h3>{{ item.username }}</h3><p>{{ item.status === 'ISSUED' ? '待成员确认' : invitationStatus[item.status] }} · 普通成员只读权限</p><p>有效期至 {{ new Date(item.expiresAt).toLocaleString() }}</p><p v-if="item.reviewNote">核验反馈：{{ item.reviewNote }}</p></div><button v-if="['ISSUED','CLAIMED','EXPIRED'].includes(item.status)" class="secondary-button" :disabled="busy" @click="revoke(item)">撤销邀请</button></article>
        <PaginationBar :page="invitePage" :size="20" :total="inviteTotal" :disabled="busy" fixed-size @change="changePage('invitations', $event)" />
      </section>
      <dialog v-if="selected" ref="disableDialog" class="team-dialog" aria-labelledby="disable-team-title" @cancel="cancelDisable">
        <form class="panel team-panel team-disable" @submit.prevent="disable"><h2 id="disable-team-title">确认停用成员</h2><p>将停用 {{ selected.username }} 的本企业访问权限，历史资料和合作记录不会删除。恢复需联系系统管理员。</p><label>停用原因<textarea v-model="reason" required maxlength="1000" rows="3" placeholder="例如：人员离职或不再参与企业工作。不要填写敏感身份信息。" /></label><p v-if="error" class="team-error" role="alert">{{ error }}</p><div class="team-actions"><button type="button" class="secondary-button" :disabled="busy" @click="selected = null">取消</button><button class="primary-button" :disabled="busy || !reason.trim()">确认停用</button></div></form>
      </dialog>
    </template>
  </div>
</template>
<style scoped>
.team-dialog{border:0;background:transparent;padding:0;max-width:100vw;max-height:100vh}.team-dialog::backdrop{background:rgba(15,30,50,.48)}.team-dialog .team-panel{margin:0}
.team-panel{padding:24px;margin:20px 0}.team-panel h2{font-size:20px;margin:0 0 16px}.team-panel h3{font-size:16px;margin:0}.team-panel p{line-height:1.8;color:var(--muted)}.team-note,.team-error{padding:18px;border-radius:10px;line-height:1.8;background:#edf6f4}.team-error{background:#fff1ee;color:#943d2c}.team-panel label{display:grid;gap:8px}.team-panel input,.team-panel textarea{font:inherit;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--ink);width:100%}.team-invite,.team-actions{display:flex;gap:14px;align-items:end;flex-wrap:wrap}.team-invite label{flex:1;min-width:200px}.team-link{display:grid;gap:14px;max-width:740px;margin-top:24px}.team-row,.team-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.team-row{padding:20px 0;border-top:1px solid var(--line)}.team-disable{width:min(580px,calc(100vw - 32px));max-height:90vh;overflow:auto}.team-disable .team-actions{margin-top:20px}.team-page button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:600px){.team-panel{padding:18px}.team-row{align-items:start;flex-direction:column}.team-invite{align-items:stretch;flex-direction:column}}
</style>
