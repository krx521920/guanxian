<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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
const roleMenuOpen = ref(false)
const profileButtonRef = ref<HTMLElement | null>(null)
const profileMenuRef = ref<HTMLElement | null>(null)

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

function switchRole(role: UserRole) {
  if (!auth.isDemoMode) return
  router.push(auth.switchRole(role))
  roleMenuOpen.value = false
  profileOpen.value = false
}

function toggleProfile() {
  profileOpen.value = !profileOpen.value
  if (!profileOpen.value) roleMenuOpen.value = false
}

function closeProfile() {
  profileOpen.value = false
  roleMenuOpen.value = false
}

function handleDocumentPointerDown(event: PointerEvent) {
  if (!profileOpen.value) return
  const target = event.target as Node
  if (profileButtonRef.value?.contains(target) || profileMenuRef.value?.contains(target)) return
  closeProfile()
}

function handleDocumentKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeProfile()
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  document.addEventListener('keydown', handleDocumentKeyDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
  document.removeEventListener('keydown', handleDocumentKeyDown)
})

async function logout() {
  closeProfile()
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
          ref="profileButtonRef"
          class="profile-button"
          type="button"
          :aria-expanded="profileOpen"
          aria-haspopup="menu"
          @click="toggleProfile"
        >
          <span class="avatar">{{ initials }}</span>
          <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
        </button>
        <button class="icon-button notification-button" type="button" aria-label="消息通知">
          <svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg><i />
        </button>
        <div v-if="profileOpen" ref="profileMenuRef" class="profile-menu">
          <div class="profile-menu-user">
            <span class="avatar">{{ initials }}</span>
            <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
          </div>
          <div class="profile-menu-group">
            <div
              v-if="auth.isDemoMode"
              class="profile-menu-item-wrap"
              @mouseenter="roleMenuOpen = true"
              @mouseleave="roleMenuOpen = false"
            >
              <button
                class="profile-menu-item"
                type="button"
                aria-haspopup="menu"
                :aria-expanded="roleMenuOpen"
                @click="roleMenuOpen = true"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                <span>切换身份</span>
                <svg class="menu-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
              </button>
              <div v-if="roleMenuOpen" class="profile-submenu" role="menu">
                <button
                  v-for="(user, role) in auth.demoUsers"
                  :key="role"
                  class="profile-submenu-item"
                  type="button"
                  role="menuitem"
                  @click="switchRole(role)"
                >
                  <span>{{ user.title }}</span>
                  <svg v-if="auth.user.value?.role === role" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>
                </button>
              </div>
            </div>
            <button class="profile-menu-item" type="button">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.09a2 2 0 0 1 1 1.74v.5a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.38a2 2 0 0 0-.73-2.73l-.15-.09a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2Z"/><circle cx="12" cy="12" r="3"/></svg>
              <span>设置</span>
            </button>
            <button class="profile-menu-item" type="button">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 22a10 10 0 1 1 10-10c0 2.76-2.24 5-5 5h-1.8c-.74 0-1.2.8-.82 1.43l.3.5c.44.73-.09 1.67-.94 1.67H12Z"/><circle cx="7.5" cy="10.5" r=".5"/><circle cx="10.5" cy="7.5" r=".5"/><circle cx="14" cy="7" r=".5"/><circle cx="17" cy="10" r=".5"/></svg>
              <span>主题</span>
              <svg class="menu-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
            </button>
            <button class="profile-menu-item" type="button">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 4 5 5L7 22l-5-5Z"/><path d="m14 5 1.5-1.5M6 3v4M4 5h4M19 13v4M17 15h4"/></svg>
              <span>外观</span>
              <svg class="menu-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>
            </button>
          </div>
          <button class="profile-menu-item logout-item" type="button" @click="logout">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 17l5-5-5-5M15 12H3"/><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/></svg>
            <span>退出登录</span>
          </button>
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
