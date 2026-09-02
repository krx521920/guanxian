import type { Attachment } from '../types/domain'

const KNOWLEDGE_ATTACHMENT_TYPES: Record<string, string> = {
  pdf: 'application/pdf',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  txt: 'text/plain',
  csv: 'text/csv',
}

export function hasAssociationWriteContext(
  role: string | null | undefined,
  associationId: string | null | undefined,
): boolean {
  return role !== 'SYSTEM_ADMIN' || Boolean(associationId)
}

export function isKnowledgeAttachmentSupported(
  item: Pick<Attachment, 'originalFilename' | 'mediaType' | 'scanStatus' | 'deletedAt'>,
): boolean {
  if (!isAttachmentContentAvailable(item)) return false
  const extension = item.originalFilename.split('.').pop()?.trim().toLowerCase() || ''
  const mediaType = item.mediaType.split(';', 1)[0]?.trim().toLowerCase() || ''
  return KNOWLEDGE_ATTACHMENT_TYPES[extension] === mediaType
}

export function isAttachmentContentAvailable(
  item: Pick<Attachment, 'scanStatus' | 'deletedAt'>,
): boolean {
  return !item.deletedAt && item.scanStatus === 'VALIDATED'
}

export function attachmentValidationLabel(status: string): string {
  if (status === 'VALIDATED') return '校验通过'
  if (status === 'REQUIRES_REUPLOAD') return '需重新上传'
  if (status === 'REJECTED') return '校验未通过'
  return '待校验'
}

export function normalizeMemberDeletedStatus(status: string, includeDeleted: boolean): string {
  return !includeDeleted && status === '已删除' ? '全部' : status
}
