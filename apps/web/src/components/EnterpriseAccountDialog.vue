<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { enterpriseAccountsApi as api, type EnterpriseAccount } from '../services/enterprise-accounts'
import { useAuth } from '../services/auth'
const props = defineProps<{ enterpriseId: string }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuth(), dialog = ref<HTMLDialogElement | null>(null)
const account = ref<EnterpriseAccount | null>(null), password = ref(''), username = ref(''), note = ref('')
const busy = ref(false), error = ref(''), message = ref(''), confirmed = ref(false), reveal = ref(false)
const canCreate = computed(() => account.value?.status === 'NOT_CREATED' && !account.value.existingBinding)
const canResume = computed(() => ['CREATING', 'RESETTING'].includes(account.value?.status || ''))
const canReset = computed(() => account.value?.status === 'ACTIVE')
const statusLabel = computed(() => ({ NOT_CREATED: '尚未开通', CREATING: '开户尚未完成', ACTIVE: '已绑定企业负责人', RESETTING: '密码重置尚未完成', BINDING_CHANGED: '绑定或权限已变化' }[account.value?.status || 'NOT_CREATED']))
async function load() {
  const value = await api.get(props.enterpriseId)
  account.value = value; username.value = value.username
}
async function refresh() {
  busy.value = true; error.value = ''
  try { await load() } catch (e) { error.value = e instanceof Error ? e.message : '无法读取账号状态' }
  finally { busy.value = false }
}
async function submit() {
  if (!account.value?.enabled || !confirmed.value || !note.value.trim() || busy.value) return
  if (canReset.value && !window.confirm(`确认重置 ${account.value.username} 的密码？原会话将失效，需要将新临时密码单独交给已核验的负责人。`)) return
  busy.value = true; error.value = ''; message.value = ''; password.value = ''; reveal.value = false
  try {
    const result = canCreate.value ? await api.create(account.value, username.value, note.value, confirmed.value)
      : await api.action(account.value, canResume.value ? 'resume' : 'reset-password', note.value, confirmed.value)
    account.value = result.account; password.value = result.temporaryPassword
    message.value = '操作完成。临时密码仅本次显示，请通过可信渠道交给对应负责人；首次登录必须改密。'
    confirmed.value = false; note.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作未完成'
    // A timeout does not prove a write failed. Read durable state; never replay the operation automatically.
    try { await load() } catch { account.value = null }
  } finally { busy.value = false }
}
function close() {
  if (busy.value) return
  if (password.value && !window.confirm('关闭后不能再次查看此临时密码。确认已妥善交付或记录？')) return
  password.value = ''; dialog.value?.close(); emit('close')
}
onMounted(async () => { await nextTick(); dialog.value?.showModal(); if (!auth.isDemoMode) await refresh() })
onBeforeUnmount(() => { password.value = ''; note.value = '' })
</script>
<template>
  <dialog ref="dialog" class="enterprise-account-dialog" aria-labelledby="account-dialog-title" @cancel.prevent="close">
    <section class="panel account-panel">
      <div class="account-heading"><h2 id="account-dialog-title">企业账号管理</h2><button class="icon-button" aria-label="关闭企业账号管理" :disabled="busy" @click="close">×</button></div>
      <p v-if="auth.isDemoMode">本地演示身份不能创建真实账号，请在已配置认证服务的环境使用系统管理员账号。</p>
      <template v-else>
        <p v-if="busy" role="status">正在处理，请勿重复提交…</p>
        <p v-if="error" role="alert" class="account-error">{{ error }}</p>
        <p v-if="message" role="status" class="account-note">{{ message }}</p>
        <button class="text-button" :disabled="busy" @click="refresh">刷新账号状态</button>
        <template v-if="account">
          <h3>{{ account.enterpriseName }}</h3><p>{{ statusLabel }}</p>
          <p class="account-note">开通的是本企业负责人账号，不会授予平台或协会管理员权限。企业资料修改仍需审核，不会自动公开。</p>
          <p v-if="!account.enabled" role="status">开户服务尚未启用：运维人员需要配置专用认证管理连接，当前不能开户或重置。</p>
          <p v-if="account.status === 'NOT_CREATED' && account.existingBinding">本企业已有平台账号，不能重复开户或覆盖原身份。请通过原身份管理渠道核验处理。</p>
          <p v-if="account.status === 'BINDING_CHANGED'">账号已停用、解绑或授权版本变化。不能通过重置密码恢复权限，请先进行身份核验。</p>
          <p v-if="canResume">上次操作未完成，账号不能正常进入业务系统。恢复处理只针对本次开户身份，且会重新生成临时密码；不会接管其他同名账号。</p>
          <div v-if="password" class="account-secret">
            <strong>临时登录凭据 · 仅本次显示</strong>
            <label>登录账号<input :value="account.username" readonly autocomplete="off" /></label>
            <label>临时密码<input :type="reveal ? 'text' : 'password'" :value="password" readonly autocomplete="off" @focus="($event.target as HTMLInputElement).select()" /></label>
            <div class="account-actions"><button class="text-button" @click="reveal = !reveal">{{ reveal ? '隐藏密码' : '显示密码' }}</button><button class="text-button" @click="password = ''">清除临时密码显示</button></div>
            <p>请单独交付，不要发到群聊或填写到核验说明中。管理员无法查看负责人修改后的密码。</p>
          </div>
          <form v-if="account.enabled && (canCreate || canResume || canReset)" @submit.prevent="submit">
            <label>企业登录账号<input v-model="username" :readonly="!canCreate" pattern="[a-zA-Z][a-zA-Z0-9._-]{2,63}" minlength="3" maxlength="64" required autocomplete="off" /></label>
            <label>{{ canReset ? '重置原因与接收人核验依据' : '负责人授权核验依据' }}<textarea v-model="note" rows="3" required maxlength="1000" placeholder="例如：已核验企业授权联系人。请勿填写密码或完整身份证号码。" /></label>
            <label class="account-confirm"><input v-model="confirmed" type="checkbox" required />我已核验此人代表该企业，确认直接开通负责人权限或向其交付重置密码。</label>
            <button class="primary-button" :disabled="busy || !confirmed || !note.trim()">{{ canCreate ? '开通企业账号' : canResume ? '恢复未完成操作' : '重置临时密码' }}</button>
          </form>
        </template>
      </template>
    </section>
  </dialog>
</template>
<style scoped>
.enterprise-account-dialog{padding:0;border:0;border-radius:14px;width:min(640px,calc(100vw - 32px));max-height:90vh;background:var(--panel);color:var(--ink)}.enterprise-account-dialog::backdrop{background:rgba(15,30,50,.5)}.account-panel{padding:24px;overflow-wrap:anywhere}.account-heading,.account-actions{display:flex;justify-content:space-between;gap:12px;align-items:center}.account-heading h2{margin:0;font-size:22px}.account-panel p{line-height:1.8}.account-panel label{display:grid;gap:8px;margin:16px 0}.account-panel input,.account-panel textarea{width:100%;padding:12px;font:inherit;border:1px solid var(--line);border-radius:8px;background:var(--panel);color:var(--ink)}.account-panel .account-confirm{display:flex;align-items:start;line-height:1.8}.account-confirm input{width:auto;margin-top:6px}.account-error{color:#943d2c;background:#fff1ee;padding:14px}.account-note{background:#edf6f4;padding:14px;border-radius:8px}.account-secret{border:1px solid #c19d65;border-radius:10px;padding:18px;margin:20px 0}.account-panel button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:480px){.account-panel{padding:18px}}
</style>
