<script setup lang="ts">
import { roleLabels } from '../../config/roles'
import { useLoginFlow } from './useLoginFlow'

const { auth, selectedRole, loading, localError, entry, demoRoles, selectEntry, login } = useLoginFlow()
</script>

<template>
  <section class="lg-card entry-card" aria-label="登录入口">
    <div class="lg-accent" aria-hidden="true" />
    <div class="lg-head">
      <span class="eyebrow">{{ auth.isDemoMode ? 'LOCAL DEMO' : 'SECURE ACCESS' }}</span>
      <h1>{{ entry ? entry === 'enterprise' ? '企业账号登录' : '管理员账号登录' : '欢迎来到管线智联' }}</h1>
      <p>{{ entry ? '统一认证，按账号实际授权进入工作空间。' : '选择您的入口，开始浏览或管理。' }}</p>
    </div>

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
      <div v-else class="lg-secure">
        <strong>账号身份由后台核验</strong>
        <span>入口选择不会赋予权限。企业账号进入自己的工作台，管理员进入授权后台；只读账号保持只读。</span>
      </div>
      <button class="primary-button lg-submit" type="submit" :disabled="loading">
        {{ loading ? '正在跳转…' : auth.isDemoMode ? '进入本地测试环境' : '继续统一身份登录' }} <span aria-hidden="true">→</span>
      </button>
      <button class="entry-back" type="button" :disabled="loading" @click="selectEntry(null)">← 返回入口选择</button>
    </form>
    <p v-if="localError || auth.error.value" class="form-error lg-error" role="alert">{{ localError || auth.error.value }}</p>
    <RouterLink class="visitor-entry" to="/public"><span><strong>游客浏览</strong><small>无需登录 · 仅浏览公开内容</small></span><span aria-hidden="true">→</span></RouterLink>
    <p class="entry-help">企业账号需由管理员开通或通过邀请核验绑定。暂无账号或归属有误，请联系协会管理员。<RouterLink to="/join">查看负责人绑定申请 →</RouterLink></p>
    <div v-if="auth.isDemoMode" class="demo-tip"><b>仅限本地/测试</b> 生产构建不会启用身份切换。</div>
    <div class="lg-foot"><span>统一认证 · 角色权限由后台核验 · 操作留痕可审计</span></div>
  </section>
</template>

<style scoped>
.entry-card { width: min(480px, 100%); }
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
