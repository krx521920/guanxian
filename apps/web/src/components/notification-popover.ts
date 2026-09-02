import type { NotificationMessage } from '../types/domain'

export type NotificationTab = 'all' | 'unread' | 'archived'

export const NOTIFICATION_PAGE_SIZE = 20
export const READ_ACKNOWLEDGEMENT_ERROR = '通知状态更新失败，未标记为已读，请重试。'

export interface NotificationMessageQuery {
  unreadOnly: boolean
  status?: 'ARCHIVED'
  page: number
  size: number
}

export interface ReadAcknowledgementResult {
  items: NotificationMessage[]
  notification: NotificationMessage
  acknowledged: boolean
  error: string | null
}

export function notificationQueryFor(
  tab: NotificationTab,
  page: number,
  size = NOTIFICATION_PAGE_SIZE,
): NotificationMessageQuery {
  return {
    unreadOnly: tab === 'unread',
    status: tab === 'archived' ? 'ARCHIVED' : undefined,
    page: Math.max(0, Math.floor(page)),
    size: Math.max(1, Math.floor(size)),
  }
}

export function notificationPageCount(total: number, size = NOTIFICATION_PAGE_SIZE): number {
  const safeTotal = Math.max(0, Math.floor(total))
  const safeSize = Math.max(1, Math.floor(size))
  return Math.max(1, Math.ceil(safeTotal / safeSize))
}

export function notificationPageInRange(
  page: number,
  total: number,
  size = NOTIFICATION_PAGE_SIZE,
): boolean {
  return Number.isInteger(page) && page >= 0 && page < notificationPageCount(total, size)
}

export function notificationPageCorrection(
  page: number,
  total: number,
  size = NOTIFICATION_PAGE_SIZE,
): number | null {
  const lastPage = notificationPageCount(total, size) - 1
  return notificationPageInRange(page, total, size) ? null : lastPage
}

export function isUnreadNotification(item: NotificationMessage): boolean {
  return item.readAt === null && item.status !== 'ARCHIVED'
}

export async function acknowledgeNotificationRead(
  items: NotificationMessage[],
  notification: NotificationMessage,
  markRead: (id: string) => Promise<NotificationMessage>,
): Promise<ReadAcknowledgementResult> {
  if (!isUnreadNotification(notification)) {
    return { items, notification, acknowledged: false, error: null }
  }

  try {
    const updated = await markRead(notification.id)
    if (updated.id !== notification.id || isUnreadNotification(updated)) {
      return {
        items,
        notification,
        acknowledged: false,
        error: READ_ACKNOWLEDGEMENT_ERROR,
      }
    }
    return {
      items: items.map((item) => item.id === updated.id ? updated : item),
      notification: updated,
      acknowledged: true,
      error: null,
    }
  } catch {
    return {
      items,
      notification,
      acknowledged: false,
      error: READ_ACKNOWLEDGEMENT_ERROR,
    }
  }
}
