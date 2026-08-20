<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { navigationForRole } from '../config/navigation'
import { roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'
import type { UserRole } from '../types/domain'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const mobileOpen = ref(false)
const profileOpen = ref(false)

const navItems = computed(() => auth.user.value ? navigationForRole(auth.user.value.role) : [])
const initials = computed(() => auth.user.value?.name.slice(-2) || '用户')

function switchRole(event: Event) {
  const role = (event.target as HTMLSelectElement).value as UserRole
  router.push(auth.switchRole(role))
  profileOpen.value = false
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand">
        <div class="brand-mark"><span /><span /><span /></div>
        <div><strong>管线智联</strong><small>AI 管理协作平台</small></div>
      </div>

      <div class="nav-section-label">工作空间</div>
      <nav class="main-nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" @click="mobileOpen = false">
          <NavIcon :name="item.icon" />
          <span>{{ item.label }}</span>
          <em v-if="item.badge">{{ item.badge }}</em>
        </RouterLink>
      </nav>

      <div class="sidebar-context">
        <div class="context-label">当前组织</div>
        <strong>{{ auth.user.value?.organization }}</strong>
        <span>{{ auth.user.value ? roleLabels[auth.user.value.role] : '' }}</span>
      </div>
    </aside>
    <button v-if="mobileOpen" class="sidebar-mask" aria-label="关闭导航" @click="mobileOpen = false" />

    <main class="main-area">
      <header class="topbar">
        <button class="icon-button menu-button" aria-label="打开导航" @click="mobileOpen = true">☰</button>
        <div class="crumb"><span>北京地下管线协会</span><b>/</b><strong>{{ route.meta.title }}</strong></div>
        <div class="top-actions">
          <button class="icon-button notification-button" aria-label="消息通知">
            <svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg><i />
          </button>
          <button class="profile-button" @click="profileOpen = !profileOpen">
            <span class="avatar">{{ initials }}</span>
            <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
            <span class="chevron">⌄</span>
          </button>
          <div v-if="profileOpen" class="profile-menu">
            <label>演示身份切换</label>
            <select :value="auth.user.value?.role" @change="switchRole">
              <option v-for="(user, role) in auth.demoUsers" :key="role" :value="role">{{ user.title }}</option>
            </select>
            <button @click="logout">退出登录</button>
          </div>
        </div>
      </header>
      <div class="page-container"><RouterView /></div>
    </main>
  </div>
</template>
