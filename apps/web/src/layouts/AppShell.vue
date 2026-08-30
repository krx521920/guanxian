<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { navigationForRole } from '../config/navigation'
import { useAuth } from '../services/auth'
import type { UserRole } from '../types/domain'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const mobileOpen = ref(false)
const sidebarCollapsed = ref(false)
const profileOpen = ref(false)

const navItems = computed(() => auth.user.value ? navigationForRole(auth.user.value.role) : [])
const initials = computed(() => auth.user.value?.name.slice(-2) || '用户')
const breadcrumbSection = computed(() => {
  if (route.path.startsWith('/members')) return '企业管理'
  if (route.path.startsWith('/policies')) return '政策服务'
  if (['/ecosystem', '/matching', '/collaborations'].some((path) => route.path.startsWith(path))) return '生态协作'
  return '工作空间'
})

function toggleSidebar() {
  if (window.matchMedia('(max-width: 780px)').matches) {
    mobileOpen.value = !mobileOpen.value
    return
  }
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function switchRole(event: Event) {
  if (!auth.isDemoMode) return
  const role = (event.target as HTMLSelectElement).value as UserRole
  router.push(auth.switchRole(role))
  profileOpen.value = false
}

async function logout() {
  profileOpen.value = false
  await auth.logout()
  if (auth.isDemoMode) await router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <aside id="main-sidebar" class="sidebar" :class="{ open: mobileOpen }">
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

      <div class="sidebar-profile">
        <button
          class="profile-button"
          type="button"
          :aria-expanded="profileOpen"
          aria-haspopup="menu"
          @click="profileOpen = !profileOpen"
        >
          <span class="avatar">{{ initials }}</span>
          <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
        </button>
        <button class="icon-button notification-button" type="button" aria-label="消息通知">
          <svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg><i />
        </button>
        <div v-if="profileOpen" class="profile-menu">
          <template v-if="auth.isDemoMode">
            <label>本地测试身份</label>
            <select :value="auth.user.value?.role" @change="switchRole">
              <option v-for="(user, role) in auth.demoUsers" :key="role" :value="role">{{ user.title }}</option>
            </select>
          </template>
          <button @click="logout">退出登录</button>
        </div>
      </div>
    </aside>
    <button v-if="mobileOpen" class="sidebar-mask" aria-label="关闭导航" @click="mobileOpen = false" />

    <main class="main-area">
      <header class="topbar">
        <button
          class="icon-button sidebar-trigger"
          type="button"
          aria-controls="main-sidebar"
          :aria-expanded="mobileOpen || !sidebarCollapsed"
          :aria-label="mobileOpen ? '关闭导航' : (sidebarCollapsed ? '展开侧边栏' : '收起侧边栏')"
          @click="toggleSidebar"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></svg>
        </button>
        <span class="topbar-divider" aria-hidden="true" />
        <nav class="crumb" aria-label="面包屑导航"><span>{{ breadcrumbSection }}</span><b>›</b><strong>{{ route.meta.title }}</strong></nav>
      </header>
      <div class="page-container"><RouterView /></div>
    </main>
  </div>
</template>
