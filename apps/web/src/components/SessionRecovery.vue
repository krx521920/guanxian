<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '../services/auth'

const auth = useAuth()
const route = useRoute()
const dialog = ref<HTMLDialogElement | null>(null)
const loginError = ref('')
watch([dialog, () => auth.state.sessionIssue], ([element, issue]) => {
  if (issue && !element?.open) element?.showModal()
  if (!issue) element?.close()
}, { flush: 'post' })
onBeforeUnmount(() => dialog.value?.close())

async function retry() {
  try { await auth.retrySession() } catch { /* The session state contains a safe, actionable explanation. */ }
}
async function login() {
  loginError.value = ''
  try { await auth.login(route.fullPath) } catch { loginError.value = '暂时无法打开登录服务，请检查网络后重试。' }
}
</script>

<template>
  <dialog ref="dialog" class="session-recovery" role="dialog" :aria-modal="auth.state.sessionIssue ? true : undefined" aria-labelledby="session-recovery-title" aria-describedby="session-recovery-message" @cancel.prevent @keydown.stop>
    <h2 id="session-recovery-title">连接暂时中断</h2>
    <p id="session-recovery-message">{{ auth.state.sessionIssue }}</p>
    <p>当前页面的未提交内容仍保留在本页，请先尝试恢复连接；重新登录会离开当前页面。</p>
    <p v-if="loginError" role="alert">{{ loginError }}</p>
    <div class="session-recovery-actions">
      <button type="button" class="primary-button" :disabled="auth.state.recovering" @click="retry">{{ auth.state.recovering ? '正在恢复…' : '重试连接' }}</button>
      <button type="button" class="secondary-button" :disabled="auth.state.recovering" @click="login">重新登录</button>
    </div>
  </dialog>
</template>

<style scoped>
.session-recovery { width: min(460px, calc(100vw - 32px)); box-sizing: border-box; max-height: calc(100dvh - 32px); overflow: auto; padding: 24px; color: var(--ink); background: var(--panel); border: 1px solid var(--line); border-radius: 16px; box-shadow: 0 24px 70px #08142047; }
.session-recovery::backdrop { background: #0a141c85; }
.session-recovery h2 { margin: 0 0 16px; font-size: 22px; text-align: left; }
.session-recovery p { line-height: 1.7; color: var(--muted); text-align: left; }
.session-recovery-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 20px; }
</style>
