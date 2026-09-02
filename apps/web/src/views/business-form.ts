export function splitItems(value: string): string[] {
  return [...new Set(value.split(/[\n,，、;；]+/).map((item) => item.trim()).filter(Boolean))]
}

export function nullableText(value: string): string | null {
  return value.trim() || null
}

export function apiActionMessage(reason: unknown, fallback: string): string {
  if (reason && typeof reason === 'object' && 'status' in reason) {
    if (reason.status === 403) return '当前账号没有执行该操作的权限。'
    if (reason.status === 409 || reason.status === 412) return '数据已发生变化，请刷新后重试。'
  }
  if (reason && typeof reason === 'object' && 'code' in reason && reason.code === 'REQUEST_TIMEOUT') {
    return '请求超时，服务端可能仍在处理，请刷新数据确认结果后再决定是否重试。'
  }
  return fallback
}

export function displayBusinessStatus(value: string | null | undefined): string {
  const labels: Record<string, string> = {
    DRAFT: '草稿', PENDING_REVIEW: '待审核', PUBLISHED: '已发布', ACTIVE: '进行中',
    APPROVED: '已通过', ACCEPTED: '已接受', REJECTED: '已拒绝', DISABLED: '已停用', CLOSED: '已关闭',
    PROPOSED: '待确认', PENDING_CONFIRMATION: '待双方确认', RECOMMENDED: '已推荐',
    PARTIALLY_CONFIRMED: '一方已确认', CONFIRMED: '双方已确认', INVITED: '已邀请',
    NEGOTIATING: '洽谈中', OUTCOME_PENDING: '成果待归档', ARCHIVED: '成果已归档',
    INITIAL_CONTACT: '初次联系', TECHNICAL_EXCHANGE: '技术交流',
    COMMERCIAL_NEGOTIATION: '商务洽谈', CONTRACTING: '合同推进',
    CONTRACT_SIGNED: '合同已签署', TERMINATED: '洽谈终止',
    SUCCESS: '已达成合作', NO_DEAL: '未达成合作', WITHDRAWN: '主动退出',
    ENTERPRISE: '企业发起', ASSOCIATION_RECOMMENDATION: '协会推荐',
    COOPERATION: '合作落地', CONTRACT: '合同签订', PILOT: '试点项目', TECHNICAL_RESULT: '技术成果',
    PRIVATE: '仅归档人', ENTERPRISES: '参与企业', ASSOCIATION: '协会内', PARTNERS: '合作协会', PUBLIC: '公开',
    COMPLETED: '已完成', OPEN: '已开放',
    IN_PROGRESS: '进行中', PENDING: '待处理', SUSPENDED: '已暂停', REVOKED: '已撤销',
    CANCELLED: '已取消', EXPIRED: '已到期',
  }
  return (value ? labels[value] : undefined) || value || '未知'
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
