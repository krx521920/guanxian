<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../services/auth'
import { workspaceForUser } from '../router/access'
import { captureInvitation, clearInvitation, invitationApi, invitationStatus, type EnterpriseInvitation } from '../services/enterprise-onboarding'

const auth = useAuth(), router = useRouter()
const token = ref(captureInvitation(window.location.hash))
// A fragment is never sent to the server; scrub it before starting an OIDC redirect.
if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
const identity = computed(() => auth.onboardingIdentity.value || (auth.user.value ? { displayName: auth.user.value.name, username: '' } : null))
const preview = ref<EnterpriseInvitation | null>(null), mine = ref<EnterpriseInvitation[]>([])
const confirmed = ref(false), busy = ref(false), error = ref(''), message = ref('')
async function load() {
  if (!identity.value) return
  busy.value = true; error.value = ''; preview.value = null
  try {
    mine.value = await invitationApi.mine()
    if (token.value) preview.value = await invitationApi.preview(token.value)
  } catch (e) { error.value = e instanceof Error ? e.message : '无法读取邀请，请重试' }
  finally { busy.value = false }
}
async function claim() {
  if (!confirmed.value || !preview.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    await invitationApi.claim(token.value)
    clearInvitation(); token.value = ''; preview.value = null
    message.value = '申请已提交。管理员核验通过后，您才可维护该企业资料。'
  } catch (e) { error.value = e instanceof Error ? e.message : '提交失败' }
  finally { busy.value = false }
  if (!error.value) await load()
}
async function refresh() {
  busy.value = true; error.value = ''
  try {
    await auth.refreshIdentity()
    if (auth.user.value) { await router.replace(workspaceForUser(auth.user.value)); return }
    await load()
  } catch (e) { error.value = e instanceof Error ? e.message : '验证失败，请重新登录' }
  finally { busy.value = false }
}
async function login() {
  busy.value = true; error.value = ''
  try { await auth.login('/join') } catch { error.value = '无法打开统一认证，请稍后重试'; busy.value = false }
}
async function logout() { clearInvitation(); await auth.logout() }
onMounted(load)
</script>

<template>
  <main class="join-page">
    <nav><RouterLink to="/public">← 返回公开平台</RouterLink><span>管线智联 · 企业服务</span></nav>
    <section class="join-card">
      <p class="eyebrow">企业负责人接入</p><h1>连接账号与您的企业</h1>
      <p>沿用协会已建档的企业资料，不重复创建企业。确认申请后，由系统管理员核验负责人身份。</p>
      <ol class="join-steps"><li>统一账号登录</li><li>确认邀请</li><li>管理员核验</li><li>维护我的企业</li></ol>
      <p v-if="error" role="alert" class="join-error">{{ error }}</p>
      <p v-if="message" role="status" class="join-note">{{ message }}</p>
      <template v-if="!identity">
        <h2>先登录邀请指定的账号</h2>
        <p>请使用协会邀请中指定的统一认证账号。还没有账号？请先联系协会或系统管理员开户；此页面不设置或收集密码。</p>
        <button class="primary-button" :disabled="busy" @click="login">登录并确认邀请</button>
      </template>
      <template v-else>
        <p class="join-note">当前账号：<strong>{{ identity.displayName }}</strong> {{ identity.username }} <button class="text-button" @click="logout">退出并更换账号</button></p>
        <p v-if="busy" role="status">正在核验…</p>
        <form v-if="preview" @submit.prevent="claim">
          <h2>{{ preview.enterpriseName }}</h2><p>{{ preview.associationName }} · 指定账号 {{ preview.username }}</p>
          <p>邀请有效期至 {{ new Date(preview.expiresAt).toLocaleString() }}。开通权限：仅本企业负责人维护权限。</p>
          <label class="join-confirm"><input v-model="confirmed" type="checkbox" required />我已获授权代表这家企业，确认提交负责人绑定申请。</label>
          <button class="primary-button" :disabled="busy || !confirmed">{{ preview.status === 'CLAIMED' ? '已确认，等待审核' : '确认并提交绑定申请' }}</button>
        </form>
        <p v-else-if="!busy && !mine.length">当前没有绑定申请。请向协会索取指定给您账号的负责人邀请链接。</p>
        <article v-for="item in mine" :key="item.id" class="join-application">
          <h2>{{ item.enterpriseName }}</h2><span class="tag">{{ invitationStatus[item.status] }}</span>
          <p v-if="item.reviewNote">核验反馈：{{ item.reviewNote }}</p>
          <p v-if="item.status === 'APPROVED'">已完成绑定，点击下方重新检查权限并进入工作台。</p>
        </article>
        <div class="join-actions"><button class="secondary-button" :disabled="busy" @click="refresh">重新检查权限 / 进入我的企业</button><button v-if="error" class="text-button" :disabled="busy" @click="load">重试读取邀请</button></div>
      </template>
      <p class="join-footnote">邀请链接不等于管理权限。无需发送密码、SSH 密钥或私钥给协会人员。</p>
    </section>
  </main>
</template>
<style scoped>
.join-page{min-height:100vh;background:#f3f7f7;padding:32px max(20px,calc((100vw - 1000px)/2));color:#183636}.join-page nav{display:flex;justify-content:space-between;gap:16px;margin-bottom:36px}.join-card{background:white;border:1px solid #d9e4e3;border-radius:18px;padding:clamp(24px,4vw,48px);line-height:1.8}.join-card h1{font-size:clamp(24px,3vw,34px);margin:8px 0}.join-card h2{font-size:19px;margin:16px 0 8px}.join-card p{color:#52696a}.eyebrow{font-weight:700;letter-spacing:.1em}.join-steps{display:flex;flex-wrap:wrap;gap:12px 30px;padding:20px 24px;background:#eff7f5;border-radius:10px;margin:24px 0}.join-note{background:#edf6f4;padding:14px;border-radius:8px}.join-error{background:#fff1ee;padding:14px;border-radius:8px;color:#943d2c!important}.join-confirm{display:flex;gap:10px;align-items:flex-start;margin:24px 0}.join-confirm input{margin-top:8px}.join-application{border-bottom:1px solid #e3e9e9;padding:16px 0}.join-actions{margin-top:24px;display:flex;flex-wrap:wrap;gap:12px}.join-footnote{font-size:12px;margin-top:32px}.join-page button:disabled{opacity:.5;cursor:not-allowed}
</style>
