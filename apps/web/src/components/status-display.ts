const statusLabels: Record<string, string> = {
  ACTIVE: '已认证',
  PENDING_REVIEW: '待审核',
  INCOMPLETE: '待完善',
  DISABLED: '已停用',
  PUBLISHED: '已发布',
  DRAFT: '草稿',
  OPEN: '待受理',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  PENDING_CONFIRMATION: '待确认',
  RECOMMENDED: '已推荐',
  CONFIRMED: '已确认',
  CLOSED: '已关闭',
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
}

export function displayStatus(value: string): string {
  return statusLabels[value] || value
}
