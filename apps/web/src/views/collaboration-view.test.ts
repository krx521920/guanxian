import { describe, expect, it, vi } from 'vitest'
import type { CollaborationActivity, CollaborationHistory } from '../types/domain'
import {
  canOperateCollaborationDetail,
  saveActivityThenRefreshHistory,
} from './collaboration-view'

const activity: CollaborationActivity = {
  id: 1,
  type: 'PROGRESS_NOTE',
  detail: '双方完成现场确认',
  actorSubject: 'enterprise-a',
  occurredAt: '2026-08-31T00:00:00Z',
}

const history: CollaborationHistory = {
  id: 2,
  version: 3,
  action: 'ADD_ACTIVITY',
  actorSubject: 'enterprise-a',
  snapshot: { detail: activity.detail },
  occurredAt: '2026-08-31T00:00:00Z',
}

describe('协作详情交互闭环', () => {
  it('详情未成功加载或已报错时拒绝使用旧快照操作', () => {
    expect(canOperateCollaborationDetail(false, false, '')).toBe(false)
    expect(canOperateCollaborationDetail(true, true, '')).toBe(false)
    expect(canOperateCollaborationDetail(true, false, '加载失败')).toBe(false)
    expect(canOperateCollaborationDetail(true, false, '')).toBe(true)
  })

  it('进展保存成功后历史刷新失败不会把保存结果误报为失败', async () => {
    const save = vi.fn().mockResolvedValue(activity)
    const refresh = vi.fn().mockRejectedValue(new Error('history unavailable'))

    const result = await saveActivityThenRefreshHistory(save, refresh)

    expect(save).toHaveBeenCalledOnce()
    expect(refresh).toHaveBeenCalledOnce()
    expect(result.activity).toEqual(activity)
    expect(result.histories).toBeNull()
    expect(result.historyRefreshFailed).toBe(true)
  })

  it('进展与历史均成功时一次性返回最新闭环数据', async () => {
    const result = await saveActivityThenRefreshHistory(
      () => Promise.resolve(activity),
      () => Promise.resolve([history]),
    )

    expect(result.histories).toEqual([history])
    expect(result.historyRefreshFailed).toBe(false)
  })
})
