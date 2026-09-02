<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Building2, FileText, GitMerge, Handshake, MessageSquareText } from '@lucide/vue'
import { platformApi } from '../services/platform-api'
import type { NotificationMessage, NotificationMessagePage } from '../types/domain'
import {
  acknowledgeNotificationRead,
  isUnreadNotification,
  notificationPageCorrection,
  notificationPageCount,
  notificationPageInRange,
  notificationQueryFor,
  type NotificationTab,
} from './notification-popover'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  close: []
  unreadCount: [count: number]
}>()

const router = useRouter()
const activeTab = ref<NotificationTab>('all')
const items = ref<NotificationMessage[]>([])
const resultPage = ref<NotificationMessagePage | null>(null)
const loading = ref(false)
const loadError = ref('')
const interactionError = ref('')
const markingId = ref<string | null>(null)
const currentPage = ref(0)
let requestSequence = 0
let unreadCountSequence = 0

const tabs: Array<{ value: NotificationTab; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'unread', label: '未读' },
  { value: 'archived', label: '已归档' },
]

const totalPages = computed(() => notificationPageCount(resultPage.value?.total ?? 0))
const hasPreviousPage = computed(() => currentPage.value > 0)
const hasNextPage = computed(() => currentPage.value + 1 < totalPages.value)

const notificationIcons = {
  POLICY: FileText,
  MATCH: GitMerge,
  COLLABORATION: Handshake,
  MEMBER: Building2,
}

function iconFor(type: string) {
  return notificationIcons[type as keyof typeof notificationIcons] || MessageSquareText
}

async function refreshUnreadCount() {
  const sequence = ++unreadCountSequence
  try {
    const page = await platformApi.notificationMessages({ unreadOnly: true, page: 0, size: 1 })
    if (sequence === unreadCountSequence) emit('unreadCount', page.total)
  } catch { /* Keep the last confirmed server count; never invent one. */ }
}

async function loadNotifications(page = currentPage.value, allowPageCorrection = true) {
  if (!props.open) return
  const sequence = ++requestSequence
  const requestedTab = activeTab.value
  loading.value = true
  loadError.value = ''
  interactionError.value = ''
  items.value = []
  resultPage.value = null
  try {
    const response = await platformApi.notificationMessages(notificationQueryFor(requestedTab, page))
    if (sequence !== requestSequence) return
    if (activeTab.value !== requestedTab) return
    const correctedPage = notificationPageCorrection(response.page, response.total, response.size)
    if (correctedPage !== null) {
      if (allowPageCorrection) {
        await loadNotifications(correctedPage, false)
        return
      }
      loadError.value = '通知分页信息异常，请重新加载。'
      return
    }
    resultPage.value = response
    items.value = response.items
    currentPage.value = response.page
    if (requestedTab === 'unread') emit('unreadCount', response.total)
  } catch {
    if (sequence !== requestSequence) return
    if (activeTab.value !== requestedTab) return
    loadError.value = '通知暂时无法加载，请稍后重试。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function changePage(page: number) {
  if (loading.value || !notificationPageInRange(page, resultPage.value?.total ?? 0)) return
  void loadNotifications(page)
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
  if (markingId.value !== null) return
  interactionError.value = ''
  if (isUnreadNotification(item)) {
    markingId.value = item.id
    const result = await acknowledgeNotificationRead(
      items.value,
      item,
      platformApi.markNotificationRead,
    )
    markingId.value = null
    if (result.error) {
      interactionError.value = result.error
      return
    }
    items.value = result.items
    if (result.acknowledged) await refreshUnreadCount()
    if (activeTab.value === 'unread') {
      await loadNotifications(currentPage.value)
      if (!loadError.value && items.value.length === 0 && currentPage.value > 0) {
        await loadNotifications(currentPage.value - 1)
      }
    }
  }
  const path = resourcePath(item)
  if (path) await router.push(path)
  emit('close')
}

watch(() => props.open, (open) => {
  if (open) loadNotifications()
}, { immediate: true })
watch(activeTab, () => {
  currentPage.value = 0
  void loadNotifications(0)
})
onMounted(refreshUnreadCount)
onBeforeUnmount(() => {
  requestSequence += 1
  unreadCountSequence += 1
})
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

    <div class="notification-list" aria-live="polite" :aria-busy="loading">
      <div v-if="loading" class="notification-state">正在加载通知…</div>
      <div v-else-if="loadError" class="notification-state notification-error" role="alert">
        <span>{{ loadError }}</span>
        <button type="button" @click="loadNotifications()">重新加载</button>
      </div>
      <div v-else-if="items.length === 0" class="notification-state">
        {{ activeTab === 'unread' ? '暂无未读通知' : activeTab === 'archived' ? '暂无已归档通知' : '暂无通知' }}
      </div>
      <template v-else>
        <div v-if="interactionError" class="notification-inline-error" role="alert">
          {{ interactionError }}
        </div>
        <button
          v-for="item in items"
          :key="item.id"
          class="notification-item"
          :class="{ unread: isUnreadNotification(item) }"
          type="button"
          :disabled="markingId !== null"
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
          <i v-if="isUnreadNotification(item)" class="unread-indicator" aria-label="未读" />
        </button>
      </template>
    </div>
    <footer v-if="!loading && !loadError && resultPage && resultPage.total > 0" class="notification-pagination">
      <span>第 {{ currentPage + 1 }} / {{ totalPages }} 页 · 共 {{ resultPage.total }} 条</span>
      <div>
        <button type="button" :disabled="!hasPreviousPage" @click="changePage(currentPage - 1)">上一页</button>
        <button type="button" :disabled="!hasNextPage" @click="changePage(currentPage + 1)">下一页</button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.notification-popover { overflow: hidden; border: 1px solid var(--line); border-radius: 14px; color: var(--ink); background: var(--panel); box-shadow: 0 18px 48px rgba(14, 28, 45, .24); }
.notification-tabs { height: 52px; padding: 0 14px; border-bottom: 1px solid var(--line); display: flex; align-items: stretch; gap: 24px; }
.notification-tabs button { position: relative; padding: 0 2px; border: 0; color: var(--muted); background: transparent; font-size: 13px; font-weight: 650; }
.notification-tabs button::after { content: ''; position: absolute; right: 0; bottom: 0; left: 0; height: 2px; border-radius: 2px 2px 0 0; background: transparent; }
.notification-tabs button.active { color: var(--ink); }
.notification-tabs button.active::after { background: var(--primary); }
.notification-list { height: 332px; overflow-y: auto; overscroll-behavior: contain; }
.notification-item { width: 100%; min-height: 76px; padding: 11px 14px; border: 0; border-bottom: 1px solid var(--line); color: var(--ink); background: transparent; display: grid; grid-template-columns: 32px minmax(0, 1fr) 8px; gap: 10px; align-items: start; text-align: left; transition: background-color .16s ease; }
.notification-item:disabled { cursor: wait; opacity: .62; }
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
.notification-error { color: var(--danger); }
.notification-inline-error { padding: 9px 14px; border-bottom: 1px solid color-mix(in srgb, var(--danger) 25%, var(--line)); color: var(--danger); background: color-mix(in srgb, var(--danger) 7%, var(--panel)); font-size: 11px; line-height: 1.5; }
.notification-pagination { min-height: 48px; padding: 8px 12px; border-top: 1px solid var(--line); color: var(--muted); display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 10px; }
.notification-pagination > div { display: flex; gap: 6px; }
.notification-pagination button { padding: 5px 8px; border: 1px solid var(--line); border-radius: 6px; color: var(--primary); background: var(--panel); font-size: 10px; }
.notification-pagination button:disabled { color: var(--muted); cursor: not-allowed; opacity: .55; }
</style>
