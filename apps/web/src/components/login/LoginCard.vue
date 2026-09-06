<script setup lang="ts">
import { ROLES, type UserRole } from '../../types/domain'
import { roleDescriptions, roleLabels } from '../../config/roles'
import { useLoginFlow } from './useLoginFlow'

const { auth, selectedRole, loading, localError, login } = useLoginFlow()

const roleMarks: Record<UserRole, string> = {
  SYSTEM_ADMIN: '系',
  ASSOCIATION_ADMIN: '协',
  ASSOCIATION_OPERATOR: '协',
  ENTERPRISE_ADMIN: '企',
  ENTERPRISE_MEMBER: '企',
}
const roleTones: Record<UserRole, string> = {
  SYSTEM_ADMIN: 'sys',
  ASSOCIATION_ADMIN: 'assoc',
  ASSOCIATION_OPERATOR: 'assoc',
  ENTERPRISE_ADMIN: 'ent',
  ENTERPRISE_MEMBER: 'ent',
}
</script>

<template>
  <form class="lg-card" @submit.prevent="login">
    <div class="lg-accent" aria-hidden="true" />
    <div class="lg-head">
      <span class="eyebrow">{{ auth.isDemoMode ? 'LOCAL DEMO ENVIRONMENT' : 'SECURE ACCESS · UNIFIED IDENTITY' }}</span>
      <h1>{{ auth.isDemoMode ? '选择测试身份' : '统一身份认证登录' }}</h1>
      <p>{{ auth.isDemoMode
        ? '演示身份仅在本机预览环境有效；生产环境仅提供统一身份认证登录。'
        : '将跳转至统一身份认证平台完成登录，返回后自动进入对应工作台。平台不留存密码。' }}</p>
    </div>

    <fieldset v-if="auth.isDemoMode" class="lg-roles">
      <legend>演示身份 · 单选</legend>
      <label v-for="role in ROLES" :key="role" :class="['lg-role', { selected: selectedRole === role }]">
        <input v-model="selectedRole" type="radio" name="role" :value="role" />
        <span class="lg-role-mark" :class="`tone-${roleTones[role]}`">{{ roleMarks[role] }}</span>
        <span class="lg-role-text"><strong>{{ roleLabels[role] }}</strong><small>{{ roleDescriptions[role] }}</small></span>
        <span class="lg-role-check" aria-hidden="true">✓</span>
      </label>
    </fieldset>

    <div v-else class="lg-secure">
      <strong>什么是统一身份认证？</strong>
      <span>平台使用身份提供方签发的短期令牌完成登录，角色与权限由平台后端统一校验，登录后的每一次操作都留痕可审计。</span>
    </div>

    <p v-if="localError || auth.error.value" class="form-error lg-error" role="alert">{{ localError || auth.error.value }}</p>

    <button class="primary-button lg-submit" type="submit" :disabled="loading">
      {{ loading
        ? (auth.isDemoMode ? '正在进入…' : '正在跳转…')
        : (auth.isDemoMode ? '进入测试工作台' : '前往统一身份认证') }}<span aria-hidden="true">→</span>
    </button>

    <div class="lg-foot">
      <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.4" aria-hidden="true">
        <rect x="3.5" y="7" width="9" height="6.5" rx="1.4" />
        <path d="M5.8 7V5.2a2.2 2.2 0 0 1 4.4 0V7" />
      </svg>
      <span>{{ auth.isDemoMode
        ? '演示数据不出本机 · 生产构建自动关闭身份切换'
        : '短期令牌登录 · 角色权限后端校验 · 操作留痕可审计' }}</span>
    </div>
  </form>
</template>
