import type { CollaborationActivity, CollaborationHistory } from '../types/domain'

export interface ActivitySaveResult {
  activity: CollaborationActivity
  histories: CollaborationHistory[] | null
  historyRefreshFailed: boolean
}

export function canOperateCollaborationDetail(
  ready: boolean,
  loading: boolean,
  error: string,
): boolean {
  return ready && !loading && !error
}

export async function saveActivityThenRefreshHistory(
  save: () => Promise<CollaborationActivity>,
  refreshHistory: () => Promise<CollaborationHistory[]>,
): Promise<ActivitySaveResult> {
  const activity = await save()
  try {
    return {
      activity,
      histories: await refreshHistory(),
      historyRefreshFailed: false,
    }
  } catch {
    return { activity, histories: null, historyRefreshFailed: true }
  }
}
