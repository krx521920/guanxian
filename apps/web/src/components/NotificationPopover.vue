<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
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
const items = ref<NotificationMessage[]>([])
const loading = ref(false)
const errorMessage = ref('')
let requestSequence = 0

const tabs: Array<{ value: NotificationTab; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'unread', label: '未读' },
  { value: 'archived', label: '已归档' },
]

const visibleItems = computed(() => activeTab.value === 'archived'
  ? items.value.filter((item) => item.status === 'ARCHIVED')
  : items.value)

async function refreshUnreadCount() {
  try {
    const page = await platformApi.notificationMessages(true)
    emit('unreadCount', page.total)
  } catch {
    emit('unreadCount', 0)
  }
}

async function loadNotifications() {
  if (!props.open) return
  const sequence = ++requestSequence
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await platformApi.notificationMessages(activeTab.value === 'unread')
    if (sequence !== requestSequence) return
    items.value = page.items
    if (activeTab.value === 'unread') emit('unreadCount', page.total)
  } catch (error) {
    if (sequence !== requestSequence) return
    errorMessage.value = error instanceof Error ? error.message : '通知加载失败'
    items.value = []
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
      await refreshUnreadCount()
    } catch {
      // Navigation remains available even if the read acknowledgement fails.
    }
  }
  const path = resourcePath(item)
  if (path) await router.push(path)
  emit('close')
}

watch(() => props.open, (open) => {
  if (open) loadNotifications()
})
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
      <div v-if="loading" class="notification-state">正在加载通知…</div>
      <div v-else-if="errorMessage" class="notification-state error-state">
        <span>{{ errorMessage }}</span>
        <button type="button" @click="loadNotifications">重新加载</button>
      </div>
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
            <svg viewBox="0 0 24 24"><path d="M21 15a4 4 0 0 1-4 4H8l-5 3v-7a4 4 0 0 1-1-2.65V8a4 4 0 0 1 4-4h11a4 4 0 0 1 4 4Z"/></svg>
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
.notification-popover { overflow: hidden; border: 1px solid var(--line); border-radius: 12px; color: var(--ink); background: var(--panel); box-shadow: 0 18px 48px rgba(14, 28, 45, .24); }
.notification-tabs { height: 54px; padding: 0 14px; border-bottom: 1px solid var(--line); display: flex; align-items: stretch; gap: 22px; }
.notification-tabs button { position: relative; padding: 0 2px; border: 0; color: var(--muted); background: transparent; font-size: 13px; font-weight: 600; }
.notification-tabs button::after { content: ''; position: absolute; right: 0; bottom: 0; left: 0; height: 2px; border-radius: 2px 2px 0 0; background: transparent; }
.notification-tabs button.active { color: var(--ink); }
.notification-tabs button.active::after { background: var(--primary); }
.notification-list { max-height: 390px; overflow-y: auto; overscroll-behavior: contain; }
.notification-item { width: 100%; min-height: 88px; padding: 15px 16px; border: 0; border-bottom: 1px solid var(--line); color: var(--ink); background: transparent; display: grid; grid-template-columns: 34px minmax(0, 1fr) 8px; gap: 11px; align-items: start; text-align: left; }
.notification-item:last-child { border-bottom: 0; }
.notification-item:hover { background: var(--surface-hover); }
.notification-item.unread { background: color-mix(in srgb, var(--primary-soft) 42%, var(--panel)); }
.notification-item.unread:hover { background: color-mix(in srgb, var(--primary-soft) 64%, var(--panel)); }
.notification-type-icon { width: 32px; height: 32px; border-radius: 50%; color: var(--primary); background: var(--primary-soft); display: grid; place-items: center; }
.notification-type-icon svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.notification-copy { min-width: 0; display: grid; gap: 4px; }
.notification-copy strong { overflow: hidden; color: var(--ink); font-size: 12px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.notification-copy > span { overflow: hidden; color: var(--muted); font-size: 10px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.notification-copy time { color: #929dab; font-size: 9px; }
.unread-indicator { width: 7px; height: 7px; margin-top: 5px; border-radius: 50%; background: var(--primary); }
.notification-state { min-height: 150px; padding: 28px; color: var(--muted); display: grid; place-content: center; gap: 12px; text-align: center; font-size: 12px; }
.notification-state button { padding: 6px 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--primary); background: var(--panel); }
.error-state { color: var(--danger); }
</style>
