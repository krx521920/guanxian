<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Building2, FileText, GitMerge, Handshake, MessageSquareText } from '@lucide/vue'
import { notifications as notificationSamples } from '../mocks/data'
import { platformApi } from '../services/platform-api'
import type { NotificationMessage } from '../types/domain'

type NotificationTab = 'all' | 'unread' | 'archived'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  close: []
  unreadCount: [count: number]
}>()

const router = useRouter()
const activeTab = ref<NotificationTab>('all')
const fallbackItems = ref(notificationSamples.map((item) => ({ ...item })))
const items = ref<NotificationMessage[]>(fallbackItems.value.map((item) => ({ ...item })))
const loading = ref(false)
const tabCache = new Map<NotificationTab, NotificationMessage[]>()
let requestSequence = 0

const tabs: Array<{ value: NotificationTab; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'unread', label: '未读' },
  { value: 'archived', label: '已归档' },
]

const visibleItems = computed(() => activeTab.value === 'archived'
  ? items.value.filter((item) => item.status === 'ARCHIVED')
  : items.value)

const notificationIcons = {
  POLICY: FileText,
  MATCH: GitMerge,
  COLLABORATION: Handshake,
  MEMBER: Building2,
}

function iconFor(type: string) {
  return notificationIcons[type as keyof typeof notificationIcons] || MessageSquareText
}

function fallbackFor(tab: NotificationTab): NotificationMessage[] {
  if (tab === 'unread') {
    return fallbackItems.value.filter((item) => item.readAt === null && item.status !== 'ARCHIVED')
  }
  if (tab === 'archived') return fallbackItems.value.filter((item) => item.status === 'ARCHIVED')
  return fallbackItems.value
}

function emitFallbackUnreadCount() {
  emit('unreadCount', fallbackFor('unread').length)
}

async function refreshUnreadCount() {
  try {
    const page = await platformApi.notificationMessages(true)
    emit('unreadCount', page.total || fallbackFor('unread').length)
  } catch {
    emitFallbackUnreadCount()
  }
}

async function loadNotifications() {
  if (!props.open) return
  const sequence = ++requestSequence
  const requestedTab = activeTab.value
  const cachedItems = tabCache.get(requestedTab)
  items.value = cachedItems || fallbackFor(requestedTab)
  loading.value = items.value.length === 0
  try {
    const page = await platformApi.notificationMessages(requestedTab === 'unread')
    if (sequence !== requestSequence) return
    const nextItems = page.items.length > 0 ? page.items : fallbackFor(requestedTab)
    tabCache.set(requestedTab, nextItems)
    if (activeTab.value === requestedTab) items.value = nextItems
    if (requestedTab === 'unread') emit('unreadCount', page.total || fallbackFor('unread').length)
  } catch {
    if (sequence !== requestSequence) return
    const nextItems = fallbackFor(requestedTab)
    tabCache.set(requestedTab, nextItems)
    if (activeTab.value === requestedTab) items.value = nextItems
    emitFallbackUnreadCount()
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function relativeTime(value: string) {
  const timestamp = new Date(value).getTime()
  if (!Number.isFinite(timestamp)) return ''
  const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60_000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(timestamp)
}

function resourcePath(item: NotificationMessage) {
  if (item.resourceType === 'POLICY_DOCUMENT') return '/policies'
  if (item.resourceType === 'ECOSYSTEM_MATCH') return '/matching'
  if (item.resourceType === 'COLLABORATION') return '/collaborations'
  if (item.resourceType === 'MEMBER_ENTERPRISE') return '/members'
  return null
}

async function openNotification(item: NotificationMessage) {
  if (item.readAt === null) {
    try {
      const updated = await platformApi.markNotificationRead(item.id)
      items.value = items.value.map((value) => value.id === updated.id ? updated : value)
      tabCache.forEach((values, tab) => {
        tabCache.set(tab, values.map((value) => value.id === updated.id ? updated : value))
      })
      await refreshUnreadCount()
    } catch {
      const readAt = new Date().toISOString()
      fallbackItems.value = fallbackItems.value.map((value) => value.id === item.id
        ? { ...value, status: 'READ', readAt }
        : value)
      items.value = items.value.map((value) => value.id === item.id
        ? { ...value, status: 'READ', readAt }
        : value)
      tabCache.forEach((values, tab) => {
        tabCache.set(tab, values.map((value) => value.id === item.id
          ? { ...value, status: 'READ', readAt }
          : value))
      })
      emitFallbackUnreadCount()
    }
  }
  const path = resourcePath(item)
  if (path) await router.push(path)
  emit('close')
}

watch(() => props.open, (open) => {
  if (open) loadNotifications()
}, { immediate: true })
watch(activeTab, () => loadNotifications())
onMounted(refreshUnreadCount)
</script>

<template>
  <section class="notification-popover" aria-label="通知列表">
    <header class="notification-tabs" role="tablist" aria-label="通知筛选">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.value"
        :class="{ active: activeTab === tab.value }"
        @click="activeTab = tab.value"
      >
        {{ tab.label }}
      </button>
    </header>

    <div class="notification-list" aria-live="polite">
      <div v-if="loading && visibleItems.length === 0" class="notification-state">正在加载通知…</div>
      <div v-else-if="visibleItems.length === 0" class="notification-state">
        {{ activeTab === 'unread' ? '暂无未读通知' : activeTab === 'archived' ? '暂无已归档通知' : '暂无通知' }}
      </div>
      <template v-else>
        <button
          v-for="item in visibleItems"
          :key="item.id"
          class="notification-item"
          :class="{ unread: item.readAt === null }"
          type="button"
          @click="openNotification(item)"
        >
          <span class="notification-type-icon" :data-type="item.notificationType" aria-hidden="true">
            <component :is="iconFor(item.notificationType)" />
          </span>
          <span class="notification-copy">
            <strong>{{ item.title }}</strong>
            <span>{{ item.body }}</span>
            <time :datetime="item.createdAt">{{ relativeTime(item.createdAt) }}</time>
          </span>
          <i v-if="item.readAt === null" class="unread-indicator" aria-label="未读" />
        </button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.notification-popover { overflow: hidden; border: 1px solid var(--line); border-radius: 14px; color: var(--ink); background: var(--panel); box-shadow: 0 18px 48px rgba(14, 28, 45, .24); }
.notification-tabs { height: 52px; padding: 0 14px; border-bottom: 1px solid var(--line); display: flex; align-items: stretch; gap: 24px; }
.notification-tabs button { position: relative; padding: 0 2px; border: 0; color: var(--muted); background: transparent; font-size: 13px; font-weight: 650; }
.notification-tabs button::after { content: ''; position: absolute; right: 0; bottom: 0; left: 0; height: 2px; border-radius: 2px 2px 0 0; background: transparent; }
.notification-tabs button.active { color: var(--ink); }
.notification-tabs button.active::after { background: var(--primary); }
.notification-list { max-height: 380px; overflow-y: auto; overscroll-behavior: contain; }
.notification-item { width: 100%; min-height: 76px; padding: 11px 14px; border: 0; border-bottom: 1px solid var(--line); color: var(--ink); background: transparent; display: grid; grid-template-columns: 32px minmax(0, 1fr) 8px; gap: 10px; align-items: start; text-align: left; transition: background-color .16s ease; }
.notification-item:last-child { border-bottom: 0; }
.notification-item:hover { background: var(--surface-hover); }
.notification-item.unread { background: color-mix(in srgb, var(--primary-soft) 42%, var(--panel)); }
.notification-item.unread:hover { background: color-mix(in srgb, var(--primary-soft) 64%, var(--panel)); }
.notification-type-icon { width: 32px; height: 32px; border-radius: 8px; color: var(--primary); background: var(--primary-soft); display: grid; place-items: center; }
.notification-type-icon svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.notification-copy { min-width: 0; display: grid; gap: 3px; }
.notification-copy strong { overflow: hidden; color: var(--ink); font-size: 12px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.notification-copy > span { overflow: hidden; color: var(--muted); font-size: 10px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.notification-copy time { color: #929dab; font-size: 9.5px; }
.unread-indicator { width: 7px; height: 7px; margin-top: 5px; border-radius: 50%; background: var(--primary); }
.notification-state { min-height: 220px; padding: 28px; color: var(--muted); display: grid; place-content: center; gap: 12px; text-align: center; font-size: 12px; }
.notification-state button { padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--primary); background: var(--panel); }
</style>
