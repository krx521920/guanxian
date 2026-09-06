<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'

const auth = useAuth()
const router = useRouter()
const error = ref('')
const busy = ref(false)
function retry() { window.location.reload() }
async function logout() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await auth.logout()
    if (auth.isDemoMode) await router.replace('/login')
  } catch {
    error.value = '本地登录状态已清除，统一认证退出未完成，请返回入口重新登录。'
  } finally { busy.value = false }
}
</script>

<template>
  <main class="access-help-page">
    <section aria-labelledby="access-help-title">
      <span class="eyebrow">账号访问说明</span>
      <h1 id="access-help-title">账号尚未完成组织绑定</h1>
      <p>身份已验证，但当前账号缺少进入工作台所需的协会或企业归属。为避免访问错误的数据，暂不加载业务工作台。</p>
      <dl v-if="auth.user.value"><div><dt>当前账号</dt><dd>{{ auth.user.value.name }}</dd></div><div><dt>后台身份</dt><dd>{{ roleLabels[auth.user.value.role] }}</dd></div></dl>
      <p>请联系协会管理员核验企业归属，再由系统管理员完成账号绑定。无需重复创建企业，也不要共用管理员账号。</p>
      <RouterLink to="/join">已有负责人邀请？前往确认或查看申请 →</RouterLink>
      <div class="access-help-actions"><button class="primary-button" type="button" @click="retry">重新检查绑定</button><button class="secondary-button" type="button" :disabled="busy" @click="logout">退出并更换账号</button></div>
      <p v-if="error" role="alert">{{ error }}</p>
      <RouterLink to="/public">先浏览公开页面 →</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.access-help-page { min-height: 100vh; padding: 40px 20px; display: grid; place-items: center; background: #f4f7fb; color: #233b55; }
.access-help-page section { width: min(620px, 100%); padding: 36px; border: 1px solid #dce5ef; border-radius: 14px; background: white; }
.access-help-page h1 { font-size: 25px; margin: 12px 0 20px; }.access-help-page p { font-size: 14px; line-height: 1.9; color: #53687f; }
.access-help-page dl { padding: 18px; background: #f1f5fa; border-radius: 8px; display: grid; gap: 10px; font-size: 14px; }.access-help-page dl div { display: flex; gap: 16px; }.access-help-page dd { margin: 0; }.access-help-page dt { color: #64748b; }
.access-help-actions { display: flex; flex-wrap: wrap; gap: 12px; margin: 24px 0; }.access-help-page a { color: #1a4d8f; font-size: 14px; }.access-help-page :is(button, a):focus-visible { outline: 3px solid #5b95d1; outline-offset: 4px; }
@media (max-width: 480px) { .access-help-page section { padding: 24px; } }
</style>
