<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { navigationForRole } from '../config/navigation'
import { roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { NotificationMessage, NotificationMessagePage, SystemAssociationOption, SystemEnterpriseOption, UserRole } from '../types/domain'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const mobileOpen = ref(false)
const profileOpen = ref(false)
const notificationOpen = ref(false)
const notificationLoading = ref(false)
const notificationError = ref('')
const notificationPage = ref<NotificationMessagePage | null>(null)
const notificationWrap = ref<HTMLElement | null>(null)
const systemAssociations = ref<SystemAssociationOption[]>([])
const systemEnterprises = ref<SystemEnterpriseOption[]>([])
const systemContextError = ref('')
const contextRevision = ref(0)

const navItems = computed(() => auth.user.value ? navigationForRole(auth.user.value.role) : [])
const initials = computed(() => auth.user.value?.name.slice(-2) || '用户')
const unreadCount = computed(() => notificationPage.value?.items.filter((item) => !item.readAt).length ?? 0)
const isSystemAdmin = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN')

async function loadSystemContextOptions() {
  if (!isSystemAdmin.value) return
  systemContextError.value = ''
  try {
    systemAssociations.value = await platformApi.systemAssociations()
    const associationId = auth.user.value?.associationId
    systemEnterprises.value = associationId
      ? await platformApi.systemEnterprises(associationId)
      : []
    if (associationId) {
      const associationName = systemAssociations.value.find((item) => item.id === associationId)?.name
      if (associationName) auth.setSystemContext(
        associationId, associationName, auth.user.value?.enterpriseId || null,
      )
    }
  } catch {
    systemContextError.value = '管理上下文加载失败'
  }
}

async function changeSystemAssociation(event: Event) {
  const associationId = (event.target as HTMLSelectElement).value || null
  const associationName = systemAssociations.value.find((item) => item.id === associationId)?.name || '全平台'
  auth.setSystemContext(associationId, associationName, null)
  systemContextError.value = ''
  try {
    systemEnterprises.value = associationId
      ? await platformApi.systemEnterprises(associationId)
      : []
    contextRevision.value += 1
  } catch {
    systemContextError.value = '代管企业范围加载失败'
  }
}

function changeSystemEnterprise(event: Event) {
  const enterpriseId = (event.target as HTMLSelectElement).value || null
  const associationId = auth.user.value?.associationId || null
  const associationName = systemAssociations.value.find((item) => item.id === associationId)?.name || '全平台'
  auth.setSystemContext(associationId, associationName, enterpriseId)
  contextRevision.value += 1
}

async function loadNotifications() {
  if (notificationLoading.value) return
  notificationLoading.value = true
  notificationError.value = ''
  try {
    notificationPage.value = await platformApi.notifications(false, 0, 20)
  } catch {
    notificationError.value = '通知暂时无法加载，请稍后重试。'
  } finally {
    notificationLoading.value = false
  }
}

function toggleNotifications() {
  notificationOpen.value = !notificationOpen.value
  profileOpen.value = false
  if (notificationOpen.value) void loadNotifications()
}

function notificationRoute(message: NotificationMessage): string | null {
  switch (message.resourceType) {
    case 'POLICY':
    case 'POLICY_DOCUMENT':
      return '/policies'
    case 'COLLABORATION':
      return '/collaborations'
    case 'ECOSYSTEM_MATCH':
    case 'MATCH':
      return '/matching'
    case 'ENTERPRISE':
    case 'MEMBER':
      return '/members'
    default:
      return null
  }
}

async function openNotification(message: NotificationMessage) {
  if (!message.readAt) {
    try {
      const updated = await platformApi.markNotificationRead(message.id)
      if (notificationPage.value) {
        notificationPage.value = {
          ...notificationPage.value,
          items: notificationPage.value.items.map((item) => item.id === updated.id ? updated : item),
        }
      }
    } catch {
      notificationError.value = '通知状态更新失败，请稍后重试。'
      return
    }
  }
  const target = notificationRoute(message)
  if (target) await router.push(target)
  notificationOpen.value = false
}

function notificationTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function closeOverlays(event: PointerEvent) {
  if (notificationOpen.value && !notificationWrap.value?.contains(event.target as Node)) {
    notificationOpen.value = false
  }
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    notificationOpen.value = false
    profileOpen.value = false
  }
}

function switchRole(event: Event) {
  if (!auth.isDemoMode) return
  const role = (event.target as HTMLSelectElement).value as UserRole
  router.push(auth.switchRole(role))
  profileOpen.value = false
  notificationOpen.value = false
  notificationPage.value = null
  void loadNotifications()
}

async function logout() {
  profileOpen.value = false
  await auth.logout()
  if (auth.isDemoMode) await router.push('/login')
}

onMounted(() => {
  window.addEventListener('pointerdown', closeOverlays)
  window.addEventListener('keydown', closeOnEscape)
  void loadNotifications()
  void loadSystemContextOptions()
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', closeOverlays)
  window.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand">
        <div class="brand-mark"><span /><span /><span /></div>
        <div><strong>管线智联</strong><small>管理协作平台</small></div>
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
        <template v-if="isSystemAdmin">
          <label for="system-association-context">管理协会</label>
          <select id="system-association-context" :value="auth.user.value?.associationId || ''" @change="changeSystemAssociation">
            <option value="">请选择协会</option>
            <option v-for="item in systemAssociations" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
          <label for="system-enterprise-context">代管企业</label>
          <select id="system-enterprise-context" :value="auth.user.value?.enterpriseId || ''" :disabled="!auth.user.value?.associationId" @change="changeSystemEnterprise">
            <option value="">不指定企业</option>
            <option v-for="item in systemEnterprises" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
          <small v-if="systemContextError" class="danger-text">{{ systemContextError }}</small>
        </template>
      </div>
    </aside>
    <button v-if="mobileOpen" class="sidebar-mask" aria-label="关闭导航" @click="mobileOpen = false" />

    <main class="main-area">
      <header class="topbar">
        <button class="icon-button menu-button" aria-label="打开导航" @click="mobileOpen = true">☰</button>
        <div class="crumb"><span>北京地下管线协会</span><b>/</b><strong>{{ route.meta.title }}</strong></div>
        <div class="top-actions">
          <div ref="notificationWrap" class="notification-wrap">
            <button
              class="icon-button notification-button"
              aria-label="消息通知"
              aria-controls="notification-panel"
              :aria-expanded="notificationOpen"
              @click="toggleNotifications"
            >
              <svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>
              <i v-if="unreadCount" />
            </button>
            <section v-if="notificationOpen" id="notification-panel" class="notification-panel" aria-label="消息通知">
              <div class="notification-panel-header">
                <div><strong>消息通知</strong><span>{{ unreadCount ? `${unreadCount} 条未读` : '暂无未读' }}</span></div>
                <button type="button" :disabled="notificationLoading" @click="loadNotifications">刷新</button>
              </div>
              <div v-if="notificationLoading && !notificationPage" class="notification-state">正在加载通知…</div>
              <div v-else-if="notificationError" class="notification-state error">
                <span>{{ notificationError }}</span>
                <button type="button" @click="loadNotifications">重新加载</button>
              </div>
              <div v-else-if="!notificationPage?.items.length" class="notification-state">
                <b>暂无通知</b>
                <span>政策、供需匹配和协作事项的最新提醒会显示在这里。</span>
              </div>
              <div v-else class="notification-list">
                <button
                  v-for="message in notificationPage.items"
                  :key="message.id"
                  type="button"
                  class="notification-item"
                  :class="{ unread: !message.readAt }"
                  @click="openNotification(message)"
                >
                  <span class="notification-type">{{ message.notificationType }}</span>
                  <span class="notification-copy">
                    <strong>{{ message.title }}</strong>
                    <small>{{ message.body }}</small>
                    <time>{{ notificationTime(message.createdAt) }}</time>
                  </span>
                </button>
              </div>
            </section>
          </div>
          <button class="profile-button" @click="profileOpen = !profileOpen">
            <span class="avatar">{{ initials }}</span>
            <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
            <span class="chevron">⌄</span>
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
      </header>
      <div class="page-container"><RouterView :key="`${route.fullPath}:${contextRevision}`" /></div>
    </main>
  </div>
</template>
