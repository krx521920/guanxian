<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ROLES, type UserRole } from '../types/domain'
import { roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const selectedRole = ref<UserRole>('ASSOCIATION_ADMIN')
const loading = ref(false)
const localError = ref<string | null>(null)

async function login() {
  loading.value = true
  localError.value = null
  try {
    if (auth.isDemoMode) {
      await router.push(auth.loginDemo(selectedRole.value))
      return
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await auth.login(redirect)
  } catch {
    localError.value = '无法发起身份认证，请联系系统管理员检查 OIDC 配置。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual">
      <div class="login-brand">
        <div class="brand-mark light"><span /><span /><span /></div>
        <strong>管线智联</strong>
      </div>
      <div class="visual-copy">
        <span class="visual-kicker">北京地下管线协会</span>
        <h1>让行业资源<br />真正连接起来</h1>
        <p>向上连接政策标准，横向协同兄弟协会，向下服务会员企业，以可信业务数据支持行业协作。</p>
        <div class="network-visual" aria-hidden="true">
          <div class="network-core"><b>协作</b><span>业务中枢</span></div>
          <div class="network-node node-a">政策</div>
          <div class="network-node node-b">协会</div>
          <div class="network-node node-c">企业</div>
          <div class="network-node node-d">场景</div>
          <i class="line-a" /><i class="line-b" /><i class="line-c" /><i class="line-d" />
        </div>
      </div>
      <small class="visual-footer">可信数据 · 规则匹配 · 协同闭环</small>
    </section>

    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <div class="mobile-brand"><div class="brand-mark"><span /><span /><span /></div><strong>管线智联</strong></div>
        <h2>欢迎使用管理协作平台</h2>
        <p>{{ auth.isDemoMode ? '请选择本地测试身份' : '使用统一身份认证安全登录' }}</p>

        <div v-if="auth.isDemoMode" class="role-options">
          <label v-for="role in ROLES" :key="role" :class="{ selected: selectedRole === role }">
            <input v-model="selectedRole" type="radio" name="role" :value="role" />
            <span class="role-radio" />
            <span><strong>{{ roleLabels[role] }}</strong><small>登录后按后台授权显示可用工作区</small></span>
          </label>
        </div>
        <div v-else class="secure-login-note">
          <strong>统一身份认证</strong>
          <span>登录后，平台将使用身份提供方签发的短期令牌，并由后端校验角色和权限。</span>
        </div>
        <p v-if="localError || auth.error.value" class="form-error">{{ localError || auth.error.value }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="loading">
          {{ loading ? '正在跳转…' : auth.isDemoMode ? '进入本地测试环境' : '统一身份登录' }} <span>→</span>
        </button>
        <div v-if="auth.isDemoMode" class="demo-tip"><b>仅限本地/测试</b> 生产构建不会启用身份切换。</div>
      </form>
    </section>
  </main>
</template>
