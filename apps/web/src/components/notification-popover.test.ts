import { describe, expect, it, vi } from 'vitest'
import type { NotificationMessage } from '../types/domain'
import {
  acknowledgeNotificationRead,
  isUnreadNotification,
  notificationPageCorrection,
  notificationPageCount,
  notificationPageInRange,
  notificationQueryFor,
  READ_ACKNOWLEDGEMENT_ERROR,
} from './notification-popover'

const unread: NotificationMessage = {
  id: 'message-1',
  userId: 'user-1',
  associationId: 'association-1',
  notificationType: 'POLICY',
  title: '政策更新',
  body: '政策内容已更新',
  resourceType: 'POLICY_DOCUMENT',
  resourceId: 'policy-1',
  status: 'DELIVERED',
  readAt: null,
  createdAt: '2026-08-31T00:00:00Z',
  deliveredAt: '2026-08-31T00:00:00Z',
}

describe('notification popover state', () => {
  it('builds mutually exclusive server queries for every tab', () => {
    expect(notificationQueryFor('all', -1)).toEqual({
      unreadOnly: false, status: undefined, page: 0, size: 20,
    })
    expect(notificationQueryFor('unread', 2)).toEqual({
      unreadOnly: true, status: undefined, page: 2, size: 20,
    })
    expect(notificationQueryFor('archived', 3, 5)).toEqual({
      unreadOnly: false, status: 'ARCHIVED', page: 3, size: 5,
    })
  })

  it('calculates page boundaries from the server total', () => {
    expect(notificationPageCount(0)).toBe(1)
    expect(notificationPageCount(20)).toBe(1)
    expect(notificationPageCount(21)).toBe(2)
    expect(notificationPageInRange(0, 0)).toBe(true)
    expect(notificationPageInRange(1, 20)).toBe(false)
    expect(notificationPageInRange(1, 21)).toBe(true)
    expect(notificationPageInRange(-1, 21)).toBe(false)
    expect(notificationPageInRange(1.5, 100)).toBe(false)
    expect(notificationPageCorrection(2, 21)).toBe(1)
    expect(notificationPageCorrection(3, 0)).toBe(0)
    expect(notificationPageCorrection(1, 21)).toBeNull()
  })

  it('returns the original list and notification when read acknowledgement fails', async () => {
    const items = [unread]
    const marker = vi.fn().mockRejectedValue(new Error('offline'))

    const result = await acknowledgeNotificationRead(items, unread, marker)

    expect(marker).toHaveBeenCalledOnce()
    expect(result.items).toBe(items)
    expect(result.notification).toBe(unread)
    expect(result.acknowledged).toBe(false)
    expect(result.error).toBe(READ_ACKNOWLEDGEMENT_ERROR)
    expect(unread.readAt).toBeNull()
    expect(unread.status).toBe('DELIVERED')
  })

  it('updates only the acknowledged item after a valid server response', async () => {
    const other: NotificationMessage = { ...unread, id: 'message-2' }
    const updated: NotificationMessage = {
      ...unread, status: 'READ', readAt: '2026-08-31T01:00:00Z',
    }
    const items = [unread, other]

    const result = await acknowledgeNotificationRead(items, unread, async () => updated)

    expect(result.items).toEqual([updated, other])
    expect(result.notification).toBe(updated)
    expect(result.acknowledged).toBe(true)
    expect(result.error).toBeNull()
  })

  it('rejects a server response that did not actually acknowledge the same message', async () => {
    const items = [unread]
    const result = await acknowledgeNotificationRead(items, unread, async () => ({ ...unread }))

    expect(result.items).toBe(items)
    expect(result.notification).toBe(unread)
    expect(result.acknowledged).toBe(false)
    expect(result.error).toBe(READ_ACKNOWLEDGEMENT_ERROR)
  })

  it('never marks an archived notification, even if legacy data has no read timestamp', async () => {
    const archived: NotificationMessage = { ...unread, status: 'ARCHIVED' }
    const marker = vi.fn()

    const result = await acknowledgeNotificationRead([archived], archived, marker)

    expect(isUnreadNotification(archived)).toBe(false)
    expect(marker).not.toHaveBeenCalled()
    expect(result.items[0]).toBe(archived)
    expect(result.acknowledged).toBe(false)
    expect(result.error).toBeNull()
  })
})
