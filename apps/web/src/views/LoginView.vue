<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { UserRole } from '../types/domain'
import { associationRoles, enterpriseRoles, roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'
import { postLoginDestination } from '../router/access'
import { safeLocalPath } from '../services/local-path'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const selectedRole = ref<UserRole>('ASSOCIATION_ADMIN')
const loading = ref(false)
const localError = ref<string | null>(null)
const entry = computed(() => route.query.entry === 'enterprise' ? 'enterprise'
  : route.query.entry === 'admin' ? 'admin' : null)
const demoRoles = computed(() => entry.value === 'enterprise' ? enterpriseRoles : [...associationRoles, 'OBSERVER'] as UserRole[])
watch(entry, (value) => {
  selectedRole.value = value === 'enterprise' ? 'ENTERPRISE_ADMIN' : 'ASSOCIATION_ADMIN'
  localError.value = null
}, { immediate: true })

function selectEntry(value: 'enterprise' | 'admin' | null) {
  const redirect = safeLocalPath(route.query.redirect)
  void router.push({ path: '/login', query: { ...(value ? { entry: value } : {}), ...(redirect !== '/' ? { redirect } : {}) } })
}

async function login() {
  if (loading.value || !entry.value) return
  loading.value = true
  localError.value = null
  try {
    if (auth.isDemoMode) {
      auth.loginDemo(selectedRole.value)
      await router.replace(postLoginDestination(router, auth.user.value!, route.query.redirect))
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
        <div><strong>管线智联</strong><span class="official-badge">官方平台</span></div>
      </div>
      <div class="visual-copy">
        <span class="visual-kicker">北京地下管线协会 · 管理协作平台</span>
        <h1>政务可信<br />企业高效 · 数据可追溯</h1>
        <p>向上连接政策标准，横向协同兄弟协会，向下服务会员企业，以可信业务数据支持行业协作。每一项数据注明来源与更新时间，每一次操作都留痕可查。</p>
        <div class="network-visual" aria-hidden="true">
          <div class="network-core"><b>协作</b><span>业务中枢</span></div>
          <div class="network-node node-a">政策</div>
          <div class="network-node node-b">协会</div>
          <div class="network-node node-c">企业</div>
          <div class="network-node node-d">场景</div>
          <i class="line-a" /><i class="line-b" /><i class="line-c" /><i class="line-d" />
        </div>
      </div>
      <small class="visual-footer">主办单位：北京地下管线协会 · 可信数据 · 规则匹配 · 协同闭环</small>
    </section>

    <section class="login-panel">
      <div class="login-card entry-card">
        <div class="mobile-brand"><div class="brand-mark"><span /><span /><span /></div><strong>北京地下管线协会管理协作平台</strong></div>
        <span class="eyebrow">{{ auth.isDemoMode ? 'LOCAL DEMO' : 'SECURE ACCESS' }}</span>
        <h2>{{ entry ? entry === 'enterprise' ? '企业账号登录' : '管理员账号登录' : '欢迎来到管线智联' }}</h2>
        <p>{{ entry ? '统一认证，按账号实际授权进入工作空间。' : '选择您的入口，开始浏览或管理。' }}</p>

        <div v-if="!entry" class="entry-options">
          <button type="button" class="entry-option" @click="selectEntry('enterprise')">
            <span class="entry-symbol" aria-hidden="true">企</span>
            <span><strong>企业登录</strong><small>查看本企业资料、政策与合作进度</small></span><span aria-hidden="true">→</span>
          </button>
          <button type="button" class="entry-option" @click="selectEntry('admin')">
            <span class="entry-symbol admin-symbol" aria-hidden="true">管</span>
            <span><strong>管理员登录</strong><small>协会运营、资料审核与后台管理</small></span><span aria-hidden="true">→</span>
          </button>
        </div>
        <form v-else @submit.prevent="login">
          <label v-if="auth.isDemoMode" class="entry-demo-role">
            本地测试账号
            <select v-model="selectedRole"><option v-for="role in demoRoles" :key="role" :value="role">{{ roleLabels[role] }}</option></select>
          </label>
          <div v-else class="secure-login-note">
            <strong>账号身份由后台核验</strong>
            <span>入口选择不会赋予权限。企业账号进入自己的工作台，管理员进入授权后台；只读账号保持只读。</span>
          </div>
          <button class="primary-button login-submit" type="submit" :disabled="loading">
            {{ loading ? '正在跳转…' : auth.isDemoMode ? '进入本地测试环境' : '继续统一身份登录' }} <span aria-hidden="true">→</span>
          </button>
          <button class="entry-back" type="button" :disabled="loading" @click="selectEntry(null)">← 返回入口选择</button>
        </form>
        <p v-if="localError || auth.error.value" class="form-error" role="alert">{{ localError || auth.error.value }}</p>
        <RouterLink class="visitor-entry" to="/public"><span><strong>游客浏览</strong><small>无需登录 · 仅浏览公开内容</small></span><span aria-hidden="true">→</span></RouterLink>
        <p class="entry-help">企业账号需由协会核验并绑定企业。暂无账号或归属有误，请联系协会管理员。<RouterLink to="/join">查看负责人绑定申请 →</RouterLink></p>
        <div v-if="auth.isDemoMode" class="demo-tip"><b>仅限本地/测试</b> 生产构建不会启用身份切换。</div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.entry-card { width: min(500px, 100%); }
.entry-options { display: grid; gap: 12px; }
.entry-option { width: 100%; padding: 18px 14px; display: flex; gap: 13px; align-items: center; text-align: left; background: #fff; color: #203852; border: 1px solid #dce4ee; border-radius: 10px; cursor: pointer; }
.entry-option:hover { border-color: #1a4d8f; background: #f4f8fd; }
.entry-option > span:nth-child(2), .visitor-entry > span:first-child { flex: 1; display: grid; gap: 6px; }
.entry-option strong, .visitor-entry strong { font-size: 16px; }
.entry-option small, .visitor-entry small { color: #64748b; font-size: 12px; line-height: 1.6; }
.entry-symbol { display: grid; place-items: center; width: 42px; height: 42px; flex-shrink: 0; border-radius: 10px; background: #e8f4f1; color: #14695d; font-weight: 700; }
.admin-symbol { background: #eaf0fa; color: #1a4d8f; }
.entry-demo-role { display: grid; gap: 8px; color: #42566d; font-size: 13px; }
.entry-demo-role select { min-height: 44px; width: 100%; padding: 8px; border: 1px solid #cbd6e3; border-radius: 8px; background: #fff; }
.entry-back { display: block; margin: 14px auto 0; padding: 8px; background: transparent; border: 0; color: #42566d; cursor: pointer; }
.visitor-entry { margin-top: 24px; padding: 20px 0 0; border-top: 1px solid #dce4ee; color: #1a4d8f; display: flex; align-items: center; gap: 12px; }
.entry-card .entry-help { margin: 22px 0 0; font-size: 12px; line-height: 1.8; color: #64748b; }
.entry-card .form-error { margin: 16px 0; }
.entry-card :is(button, select, a):focus-visible { outline: 3px solid #5b95d1; outline-offset: 4px; }
@media (max-width: 480px) { .entry-card { padding: 28px 20px; } .entry-option { padding: 16px 10px; gap: 10px; } }
</style>
