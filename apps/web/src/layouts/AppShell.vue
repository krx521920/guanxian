<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView, type RouteLocationRaw } from 'vue-router'
import NavIcon from '../components/NavIcon.vue'
import { navigationForRole } from '../config/navigation'
import { defaultRouteForRole, roleLabels } from '../config/roles'
import { useAuth } from '../services/auth'
import { createLatestRequestGate } from '../services/latest-request'
import { platformApi } from '../services/platform-api'
import {
  applyUiPreferences,
  readUiPreferences,
  saveUiPreferences,
  type Appearance,
  type PrimaryTheme,
} from '../services/ui-preferences'
import type { NotificationMessage, NotificationMessagePage, SystemAssociationOption, SystemEnterpriseOption, UserRole } from '../types/domain'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const mobileOpen = ref(false)
const sidebarCollapsed = ref(false)
const keyboardNavigation = ref(false)
const profileOpen = ref(false)
const settingsOpen = ref(false)
const settingsPrimaryTheme = ref<PrimaryTheme>('teal')
const settingsAppearance = ref<Appearance>('light')
const settingsMessage = ref('')
const notificationOpen = ref(false)
const notificationLoading = ref(false)
const notificationError = ref('')
const notificationPage = ref<NotificationMessagePage | null>(null)
const notificationTab = ref<'all' | 'unread' | 'archived'>('all')
const notificationPageIndex = ref(0)
const notificationPageSize = 10
const notificationBusyIds = ref<Set<string>>(new Set())
const unreadTotal = ref(0)
const notificationWrap = ref<HTMLElement | null>(null)
const profileWrap = ref<HTMLElement | null>(null)
const systemAssociations = ref<SystemAssociationOption[]>([])
const systemEnterprises = ref<SystemEnterpriseOption[]>([])
const systemContextError = ref('')
const contextRevision = ref(0)
let notificationRequestRevision = 0
let notificationActionRevision = 0
const systemContextRequestGate = createLatestRequestGate()
const primaryThemes = [
  { value: 'teal', label: '管网青', color: '#126b68' },
  { value: 'blue', label: '深海蓝', color: '#3f6480' },
  { value: 'violet', label: '岩层靛', color: '#5d607c' },
  { value: 'orange', label: '黄铜棕', color: '#94613c' },
  { value: 'rose', label: '勃艮第红', color: '#884a59' },
] as const

const navItems = computed(() => auth.user.value ? navigationForRole(auth.user.value.role) : [])
const initials = computed(() => auth.user.value?.name.slice(-2) || '用户')
const unreadCount = computed(() => unreadTotal.value)
const notificationPageCount = computed(() => Math.max(
  1,
  Math.ceil((notificationPage.value?.total || 0) / notificationPageSize),
))
const notificationHasNextPage = computed(() => notificationPageIndex.value + 1 < notificationPageCount.value)
const isSystemAdmin = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN')
const crumbOrganization = computed(() => auth.user.value?.organization || '管线智联平台')
const workspaceHome = computed(() => auth.user.value ? defaultRouteForRole(auth.user.value.role) : '/')

function refreshContextDependentState() {
  notificationRequestRevision += 1
  notificationActionRevision += 1
  notificationOpen.value = false
  notificationLoading.value = false
  notificationError.value = ''
  notificationPage.value = null
  notificationPageIndex.value = 0
  unreadTotal.value = 0
  contextRevision.value += 1
  void loadNotifications()
}

async function loadSystemContextOptions() {
  if (!isSystemAdmin.value) return
  const requestEpoch = systemContextRequestGate.begin()
  systemContextError.value = ''
  try {
    const associations = await platformApi.systemAssociations()
    if (!systemContextRequestGate.isCurrent(requestEpoch)) return
    systemAssociations.value = associations
    const associationId = auth.user.value?.associationId
    if (!associationId) {
      systemEnterprises.value = []
      return
    }
    const association = systemAssociations.value.find((item) => item.id === associationId)
    if (!association) {
      auth.setSystemContext(null, '全平台', null)
      systemEnterprises.value = []
      systemContextError.value = '此前选择的管理协会已失效，请重新选择'
      refreshContextDependentState()
      return
    }
    const enterprises = await platformApi.systemEnterprises(associationId)
    if (!systemContextRequestGate.isCurrent(requestEpoch)) return
    systemEnterprises.value = enterprises
    const enterpriseId = auth.user.value?.enterpriseId || null
    const validEnterpriseId = enterpriseId
      && systemEnterprises.value.some((item) => item.id === enterpriseId)
      ? enterpriseId
      : null
    auth.setSystemContext(associationId, association.name, validEnterpriseId)
    if (enterpriseId && !validEnterpriseId) {
      systemContextError.value = '此前选择的代管企业已失效，请重新选择'
      refreshContextDependentState()
    }
  } catch {
    if (systemContextRequestGate.isCurrent(requestEpoch)) {
      systemContextError.value = '管理上下文加载失败'
    }
  }
}

async function changeSystemAssociation(event: Event) {
  const requestEpoch = systemContextRequestGate.begin()
  const associationId = (event.target as HTMLSelectElement).value || null
  const associationName = systemAssociations.value.find((item) => item.id === associationId)?.name || '全平台'
  systemContextError.value = ''
  try {
    const enterprises = associationId
      ? await platformApi.systemEnterprises(associationId)
      : []
    if (!systemContextRequestGate.isCurrent(requestEpoch)) return
    auth.setSystemContext(associationId, associationName, null)
    systemEnterprises.value = enterprises
    refreshContextDependentState()
  } catch {
    if (systemContextRequestGate.isCurrent(requestEpoch)) {
      systemContextError.value = '代管企业范围加载失败'
    }
  }
}

function changeSystemEnterprise(event: Event) {
  systemContextRequestGate.invalidate()
  const enterpriseId = (event.target as HTMLSelectElement).value || null
  const associationId = auth.user.value?.associationId || null
  if (enterpriseId && !systemEnterprises.value.some((item) => item.id === enterpriseId)) {
    systemContextError.value = '所选企业不在当前协会的可管理范围内'
    return
  }
  const associationName = systemAssociations.value.find((item) => item.id === associationId)?.name || '全平台'
  auth.setSystemContext(associationId, associationName, enterpriseId)
  systemContextError.value = ''
  refreshContextDependentState()
}

async function loadNotifications() {
  const requestRevision = ++notificationRequestRevision
  notificationLoading.value = true
  notificationError.value = ''
  try {
    const query = notificationTab.value === 'unread'
      ? { unreadOnly: true, page: notificationPageIndex.value, size: notificationPageSize }
      : notificationTab.value === 'archived'
        ? { status: 'ARCHIVED', page: notificationPageIndex.value, size: notificationPageSize }
        : { page: notificationPageIndex.value, size: notificationPageSize }
    const [initialPage, unreadPage] = await Promise.all([
      platformApi.notifications(query),
      platformApi.notifications({ unreadOnly: true, page: 0, size: 1 }),
    ])
    if (requestRevision !== notificationRequestRevision) return
    let loadedPage = initialPage
    if (!loadedPage.items.length && loadedPage.total > 0 && notificationPageIndex.value > 0) {
      notificationPageIndex.value = Math.max(0, Math.ceil(loadedPage.total / notificationPageSize) - 1)
      loadedPage = await platformApi.notifications({ ...query, page: notificationPageIndex.value })
      if (requestRevision !== notificationRequestRevision) return
    }
    notificationPage.value = loadedPage
    unreadTotal.value = unreadPage.total
  } catch {
    if (requestRevision === notificationRequestRevision) {
      notificationError.value = '通知暂时无法加载，请稍后重试。'
    }
  } finally {
    if (requestRevision === notificationRequestRevision) notificationLoading.value = false
  }
}

function toggleNotifications() {
  if (notificationOpen.value) {
    closeNotifications()
    return
  }
  notificationOpen.value = true
  profileOpen.value = false
  void loadNotifications()
}

function notificationRoute(message: NotificationMessage): RouteLocationRaw | null {
  switch (message.resourceType) {
    case 'POLICY':
    case 'POLICY_DOCUMENT':
      return message.resourceId ? { path: '/policies', query: { policyId: message.resourceId } } : '/policies'
    case 'COLLABORATION':
      return message.resourceId ? { path: '/collaborations', query: { collaborationId: message.resourceId } } : '/collaborations'
    case 'ECOSYSTEM_MATCH':
    case 'MATCH':
      return message.resourceId ? { path: '/matching', query: { matchId: message.resourceId } } : '/matching'
    case 'ENTERPRISE':
    case 'MEMBER':
      return message.resourceId ? { path: '/members', query: { memberId: message.resourceId } } : '/members'
    default:
      return null
  }
}

async function openNotification(message: NotificationMessage) {
  if (notificationBusyIds.value.has(message.id)) return
  const actionRevision = notificationActionRevision
  setNotificationBusy(message.id, true)
  if (!message.readAt && message.status !== 'ARCHIVED') {
    try {
      const updated = await platformApi.markNotificationRead(message.id)
      if (actionRevision !== notificationActionRevision || !notificationOpen.value) return
      if (notificationPage.value) {
        notificationPage.value = {
          ...notificationPage.value,
          items: notificationPage.value.items.map((item) => item.id === updated.id ? updated : item),
        }
      }
      unreadTotal.value = Math.max(0, unreadTotal.value - 1)
    } catch {
      if (actionRevision !== notificationActionRevision || !notificationOpen.value) return
      notificationError.value = '通知状态更新失败，请稍后重试。'
      setNotificationBusy(message.id, false)
      return
    }
  }
  if (actionRevision !== notificationActionRevision || !notificationOpen.value) return
  const target = notificationRoute(message)
  if (target) await router.push(target)
  if (actionRevision === notificationActionRevision) closeNotifications()
  setNotificationBusy(message.id, false)
}

async function changeNotificationArchive(message: NotificationMessage, restore: boolean) {
  if (notificationBusyIds.value.has(message.id)) return
  const actionRevision = notificationActionRevision
  setNotificationBusy(message.id, true)
  notificationError.value = ''
  try {
    if (restore) await platformApi.restoreNotification(message.id)
    else await platformApi.archiveNotification(message.id)
    if (actionRevision !== notificationActionRevision || !notificationOpen.value) return
    await loadNotifications()
  } catch {
    if (actionRevision === notificationActionRevision && notificationOpen.value) {
      notificationError.value = restore ? '通知恢复失败，请稍后重试。' : '通知归档失败，请稍后重试。'
    }
  } finally {
    if (actionRevision === notificationActionRevision) setNotificationBusy(message.id, false)
  }
}

function setNotificationBusy(id: string, busy: boolean) {
  const next = new Set(notificationBusyIds.value)
  if (busy) next.add(id)
  else next.delete(id)
  notificationBusyIds.value = next
}

function selectNotificationTab(value: 'all' | 'unread' | 'archived') {
  if (notificationTab.value === value) return
  notificationTab.value = value
  notificationPageIndex.value = 0
  notificationPage.value = null
  void loadNotifications()
}

function changeNotificationPage(delta: number) {
  const next = notificationPageIndex.value + delta
  if (next < 0 || next >= notificationPageCount.value) return
  notificationPageIndex.value = next
  void loadNotifications()
}

function closeNotifications() {
  notificationRequestRevision += 1
  notificationActionRevision += 1
  notificationOpen.value = false
  notificationLoading.value = false
  notificationBusyIds.value = new Set()
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
  keyboardNavigation.value = false
  if (notificationOpen.value && !notificationWrap.value?.contains(event.target as Node)) {
    closeNotifications()
  }
  if (profileOpen.value && !profileWrap.value?.contains(event.target as Node)) {
    profileOpen.value = false
  }
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Tab') keyboardNavigation.value = true
  if (event.key === 'Escape') {
    closeNotifications()
    profileOpen.value = false
    settingsOpen.value = false
  }
}

function toggleSidebar() {
  if (window.matchMedia('(max-width: 780px)').matches) {
    mobileOpen.value = !mobileOpen.value
    return
  }
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function toggleProfile() {
  profileOpen.value = !profileOpen.value
  if (profileOpen.value) closeNotifications()
}

function openSettings() {
  const preferences = readUiPreferences(localStorage)
  settingsPrimaryTheme.value = preferences.primaryTheme
  settingsAppearance.value = preferences.appearance
  settingsMessage.value = ''
  profileOpen.value = false
  closeNotifications()
  settingsOpen.value = true
}

function closeSettings() {
  settingsOpen.value = false
  settingsMessage.value = ''
}

function saveSettings() {
  const preferences = {
    primaryTheme: settingsPrimaryTheme.value,
    appearance: settingsAppearance.value,
  }
  if (!saveUiPreferences(localStorage, preferences)) {
    settingsMessage.value = '当前浏览器未允许保存界面偏好，请检查浏览器存储权限后重试。'
    return
  }
  applyUiPreferences(document.documentElement, preferences)
  closeSettings()
}

function switchRole(event: Event) {
  if (!auth.isDemoMode) return
  const role = (event.target as HTMLSelectElement).value as UserRole
  router.push(auth.switchRole(role))
  profileOpen.value = false
  closeNotifications()
  notificationPage.value = null
  void loadNotifications()
}

async function logout() {
  profileOpen.value = false
  await auth.logout()
  if (auth.isDemoMode) await router.push('/login')
}

onMounted(() => {
  applyUiPreferences(document.documentElement, readUiPreferences(localStorage))
  window.addEventListener('pointerdown', closeOverlays)
  window.addEventListener('keydown', closeOnEscape)
  void loadNotifications()
  void loadSystemContextOptions()
})

onBeforeUnmount(() => {
  systemContextRequestGate.invalidate()
  notificationRequestRevision += 1
  notificationActionRevision += 1
  window.removeEventListener('pointerdown', closeOverlays)
  window.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'keyboard-navigation': keyboardNavigation }">
    <aside id="main-sidebar" class="sidebar" :class="{ open: mobileOpen }">
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
        <button
          class="icon-button menu-button"
          type="button"
          aria-controls="main-sidebar"
          :aria-expanded="mobileOpen || !sidebarCollapsed"
          :aria-label="mobileOpen ? '关闭导航' : sidebarCollapsed ? '展开导航' : '收起导航'"
          @click="toggleSidebar"
        >☰</button>
        <RouterLink class="topbar-back-button" :to="workspaceHome" aria-label="返回工作台" title="返回工作台">
          <span aria-hidden="true">←</span><span>返回</span>
        </RouterLink>
        <div class="crumb"><span>{{ crumbOrganization }}</span><b>/</b><strong>{{ route.meta.title }}</strong></div>
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
              <div class="notification-tabs" role="tablist" aria-label="通知状态">
                <button type="button" role="tab" :aria-selected="notificationTab === 'all'" :class="{ active: notificationTab === 'all' }" @click="selectNotificationTab('all')">全部</button>
                <button type="button" role="tab" :aria-selected="notificationTab === 'unread'" :class="{ active: notificationTab === 'unread' }" @click="selectNotificationTab('unread')">未读</button>
                <button type="button" role="tab" :aria-selected="notificationTab === 'archived'" :class="{ active: notificationTab === 'archived' }" @click="selectNotificationTab('archived')">已归档</button>
              </div>
              <div v-if="notificationLoading && !notificationPage" class="notification-state">正在加载通知…</div>
              <div v-else-if="notificationError" class="notification-state error">
                <span>{{ notificationError }}</span>
                <button type="button" @click="loadNotifications">重新加载</button>
              </div>
              <div v-else-if="!notificationPage?.items.length" class="notification-state">
                <b>{{ notificationTab === 'archived' ? '暂无已归档通知' : notificationTab === 'unread' ? '暂无未读通知' : '暂无通知' }}</b>
                <span>订阅的政策发布提醒会显示在这里，页面不会用模拟通知补位。</span>
              </div>
              <div v-else class="notification-list">
                <article
                  v-for="message in notificationPage.items"
                  :key="message.id"
                  class="notification-item"
                  :class="{ unread: !message.readAt }"
                >
                  <button type="button" class="notification-open" :disabled="notificationBusyIds.has(message.id)" @click="openNotification(message)">
                    <span class="notification-type">{{ message.notificationType === 'POLICY' ? '政策' : message.notificationType }}</span>
                    <span class="notification-copy">
                      <strong>{{ message.title }}</strong>
                      <small>{{ message.body }}</small>
                      <time>{{ notificationTime(message.createdAt) }}</time>
                    </span>
                  </button>
                  <button
                    type="button"
                    class="notification-archive-action"
                    :disabled="notificationBusyIds.has(message.id)"
                    @click.stop="changeNotificationArchive(message, message.status === 'ARCHIVED')"
                  >{{ message.status === 'ARCHIVED' ? '恢复' : '归档' }}</button>
                </article>
              </div>
              <div v-if="notificationPage && notificationPage.total > notificationPageSize" class="notification-pagination">
                <button type="button" :disabled="notificationLoading || notificationPageIndex === 0" @click="changeNotificationPage(-1)">上一页</button>
                <span>{{ notificationPageIndex + 1 }} / {{ notificationPageCount }} 页 · 共 {{ notificationPage.total }} 条</span>
                <button type="button" :disabled="notificationLoading || !notificationHasNextPage" @click="changeNotificationPage(1)">下一页</button>
              </div>
            </section>
          </div>
          <div ref="profileWrap" class="profile-wrap">
            <button
              class="profile-button"
              type="button"
              aria-controls="profile-menu"
              :aria-expanded="profileOpen"
              @click="toggleProfile"
            >
              <span class="avatar">{{ initials }}</span>
              <span class="profile-copy"><strong>{{ auth.user.value?.name }}</strong><small>{{ auth.user.value?.title }}</small></span>
              <span class="chevron" :class="{ open: profileOpen }" aria-hidden="true">⌄</span>
            </button>
            <div v-if="profileOpen" id="profile-menu" class="profile-menu">
              <template v-if="auth.isDemoMode">
                <label>本地测试身份</label>
                <select :value="auth.user.value?.role" @change="switchRole">
                  <option v-for="(user, role) in auth.demoUsers" :key="role" :value="role">{{ user.title }}</option>
                </select>
              </template>
              <button class="preferences-button" type="button" @click="openSettings">界面设置</button>
              <button type="button" @click="logout">退出登录</button>
            </div>
          </div>
        </div>
      </header>
      <div class="page-container"><RouterView :key="`${route.fullPath}:${contextRevision}`" /></div>
    </main>

    <div v-if="settingsOpen" class="settings-backdrop" @click.self="closeSettings">
      <section class="settings-dialog" role="dialog" aria-modal="true" aria-labelledby="settings-title">
        <header class="settings-dialog-head">
          <div><span>LOCAL PREFERENCES</span><h2 id="settings-title">界面设置</h2></div>
          <button class="icon-button" type="button" aria-label="关闭界面设置" @click="closeSettings">×</button>
        </header>
        <form class="settings-form" @submit.prevent="saveSettings">
          <fieldset>
            <legend>主题颜色</legend>
            <div class="settings-theme-options">
              <label v-for="item in primaryThemes" :key="item.value" :class="{ selected: settingsPrimaryTheme === item.value }">
                <input v-model="settingsPrimaryTheme" type="radio" name="primary-theme" :value="item.value" />
                <i :style="{ '--swatch': item.color }" /><span>{{ item.label }}</span>
              </label>
            </div>
          </fieldset>
          <fieldset>
            <legend>明暗模式</legend>
            <div class="settings-appearance-options">
              <label :class="{ selected: settingsAppearance === 'light' }"><input v-model="settingsAppearance" type="radio" name="appearance" value="light" /><span>浅色</span></label>
              <label :class="{ selected: settingsAppearance === 'dark' }"><input v-model="settingsAppearance" type="radio" name="appearance" value="dark" /><span>深色</span></label>
            </div>
          </fieldset>
          <p class="settings-note">界面偏好仅保存在当前浏览器。账户、身份和组织信息仍由统一身份服务管理。</p>
          <p v-if="settingsMessage" class="settings-error" role="alert">{{ settingsMessage }}</p>
          <footer class="settings-actions">
            <button class="secondary-button" type="button" @click="closeSettings">取消</button>
            <button class="primary-button" type="submit">保存设置</button>
          </footer>
        </form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.preferences-button { color: var(--ink) !important; }
.settings-backdrop { position: fixed; inset: 0; z-index: 90; padding: 24px; background: rgba(10, 20, 28, .52); display: grid; place-items: center; }
.settings-dialog { width: min(520px, 100%); max-height: calc(100vh - 48px); overflow: auto; border: 1px solid var(--line); border-radius: 14px; color: var(--ink); background: var(--panel); box-shadow: 0 24px 70px rgba(8, 20, 32, .28); }
.settings-dialog-head { padding: 20px 22px; border-bottom: 1px solid var(--line); display: flex; align-items: center; justify-content: space-between; gap: 18px; }
.settings-dialog-head span { color: var(--primary); font-size: 10px; font-weight: 700; letter-spacing: .16em; }
.settings-dialog-head h2 { margin: 4px 0 0; font-size: 21px; }
.settings-form { padding: 22px; display: grid; gap: 22px; }
.settings-form fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
.settings-form legend { margin-bottom: 10px; font-size: 13px; font-weight: 700; }
.settings-theme-options { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; }
.settings-theme-options label, .settings-appearance-options label { min-height: 48px; padding: 8px; border: 1px solid var(--line); border-radius: 9px; background: var(--primary-soft); display: flex; align-items: center; justify-content: center; gap: 7px; cursor: pointer; font-size: 12px; }
.settings-theme-options label.selected, .settings-appearance-options label.selected { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-soft); }
.settings-theme-options input, .settings-appearance-options input { position: absolute; opacity: 0; pointer-events: none; }
.settings-theme-options i { width: 18px; height: 18px; border-radius: 50%; background: var(--swatch); box-shadow: 0 0 0 1px var(--line); }
.settings-appearance-options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.settings-note { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.65; }
.settings-error { margin: -8px 0 0; padding: 10px 12px; border-radius: 8px; color: #9a3412; background: #fff2e8; font-size: 12px; }
.settings-actions { padding-top: 16px; border-top: 1px solid var(--line); display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 560px) { .settings-backdrop { padding: 12px; } .settings-theme-options { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
