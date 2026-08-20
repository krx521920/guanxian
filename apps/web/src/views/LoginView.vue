<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ROLES, type UserRole } from '../types/domain'
import { roleDescriptions, roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'

const router = useRouter()
const auth = useAuth()
const selectedRole = ref<UserRole>('ASSOCIATION_ADMIN')
const loading = ref(false)

async function login() {
  loading.value = true
  await new Promise((resolve) => window.setTimeout(resolve, 260))
  await router.push(auth.login(selectedRole.value))
  loading.value = false
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
        <p>向上连接政策标准，横向协同兄弟协会，向下服务会员企业，以 AI 构建可信、高效的地下管线产业生态。</p>
        <div class="network-visual" aria-hidden="true">
          <div class="network-core"><b>AI</b><span>生态中枢</span></div>
          <div class="network-node node-a">政策</div>
          <div class="network-node node-b">协会</div>
          <div class="network-node node-c">企业</div>
          <div class="network-node node-d">场景</div>
          <i class="line-a" /><i class="line-b" /><i class="line-c" /><i class="line-d" />
        </div>
      </div>
      <small class="visual-footer">可信数据 · 智能匹配 · 协同闭环</small>
    </section>

    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <div class="mobile-brand"><div class="brand-mark"><span /><span /><span /></div><strong>管线智联</strong></div>
        <span class="eyebrow">DEMO ACCESS</span>
        <h2>欢迎使用管理协作平台</h2>
        <p>请选择演示身份进入对应工作空间</p>

        <div class="role-options">
          <label v-for="role in ROLES" :key="role" :class="{ selected: selectedRole === role }">
            <input v-model="selectedRole" type="radio" name="role" :value="role" />
            <span class="role-radio" />
            <span><strong>{{ roleLabels[role] }}</strong><small>{{ roleDescriptions[role] }}</small></span>
          </label>
        </div>
        <button class="primary-button login-submit" type="submit" :disabled="loading">
          {{ loading ? '正在进入…' : '进入平台' }} <span>→</span>
        </button>
        <div class="demo-tip"><b>演示模式</b> 当前使用本地模拟数据，可随时在右上角切换身份。</div>
      </form>
    </section>
  </main>
</template>
