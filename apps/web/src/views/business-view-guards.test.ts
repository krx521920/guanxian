import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  attachmentValidationLabel,
  hasAssociationWriteContext,
  isAttachmentContentAvailable,
  isKnowledgeAttachmentSupported,
  normalizeMemberDeletedStatus,
} from './business-view-guards'

const viewRoot = dirname(fileURLToPath(import.meta.url))

describe('业务页面写操作守卫', () => {
  it('系统管理员只有选定协会后才能执行协会域写操作', () => {
    expect(hasAssociationWriteContext('SYSTEM_ADMIN', null)).toBe(false)
    expect(hasAssociationWriteContext('SYSTEM_ADMIN', '')).toBe(false)
    expect(hasAssociationWriteContext('SYSTEM_ADMIN', 'association-1')).toBe(true)
    expect(hasAssociationWriteContext('ASSOCIATION_ADMIN', 'association-1')).toBe(true)
    expect(hasAssociationWriteContext('ENTERPRISE_ADMIN', null)).toBe(true)
  })

  it.each([
    ['report.PDF', 'application/pdf'],
    ['policy.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    ['survey.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
    ['notes.txt', 'text/plain; charset=UTF-8'],
    ['members.csv', 'text/csv'],
  ])('允许支持的知识库文件 %s', (originalFilename, mediaType) => {
    expect(isKnowledgeAttachmentSupported({ originalFilename, mediaType, scanStatus: 'VALIDATED', deletedAt: null })).toBe(true)
  })

  it('拒绝图片、伪装扩展名、其他文本扩展名和已删除附件', () => {
    expect(isKnowledgeAttachmentSupported({ originalFilename: 'photo.png', mediaType: 'image/png', scanStatus: 'VALIDATED', deletedAt: null })).toBe(false)
    expect(isKnowledgeAttachmentSupported({ originalFilename: 'photo.pdf', mediaType: 'image/png', scanStatus: 'VALIDATED', deletedAt: null })).toBe(false)
    expect(isKnowledgeAttachmentSupported({ originalFilename: 'readme.md', mediaType: 'text/plain', scanStatus: 'VALIDATED', deletedAt: null })).toBe(false)
    expect(isKnowledgeAttachmentSupported({ originalFilename: 'policy.pdf', mediaType: 'application/pdf', scanStatus: 'VALIDATED', deletedAt: '2026-08-31T00:00:00Z' })).toBe(false)
    expect(isKnowledgeAttachmentSupported({ originalFilename: 'policy.pdf', mediaType: 'application/pdf', scanStatus: 'REQUIRES_REUPLOAD', deletedAt: null })).toBe(false)
  })

  it('只有已完成内容校验且未删除的附件可以获取内容', () => {
    expect(isAttachmentContentAvailable({ scanStatus: 'VALIDATED', deletedAt: null })).toBe(true)
    expect(isAttachmentContentAvailable({ scanStatus: 'PENDING', deletedAt: null })).toBe(false)
    expect(isAttachmentContentAvailable({ scanStatus: 'REQUIRES_REUPLOAD', deletedAt: null })).toBe(false)
    expect(isAttachmentContentAvailable({ scanStatus: 'VALIDATED', deletedAt: '2026-08-31T00:00:00Z' })).toBe(false)
  })

  it('将后端内容校验状态显示为可信中文结果', () => {
    expect(attachmentValidationLabel('VALIDATED')).toBe('校验通过')
    expect(attachmentValidationLabel('REQUIRES_REUPLOAD')).toBe('需重新上传')
    expect(attachmentValidationLabel('REJECTED')).toBe('校验未通过')
    expect(attachmentValidationLabel('PENDING')).toBe('待校验')
  })

  it('关闭已删除范围时同步退出已删除状态筛选', () => {
    expect(normalizeMemberDeletedStatus('已删除', false)).toBe('全部')
    expect(normalizeMemberDeletedStatus('已删除', true)).toBe('已删除')
    expect(normalizeMemberDeletedStatus('待审核', false)).toBe('待审核')
  })

  it('会员列表接入 latest-request gate，两个页面都接入协会写上下文守卫', () => {
    const members = readFileSync(join(viewRoot, 'MembersView.vue'), 'utf8')
    const attachments = readFileSync(join(viewRoot, 'AttachmentCenterView.vue'), 'utf8')

    expect(members).toContain('createLatestRequestGate')
    expect(members).toContain('memberLoadGate.begin()')
    expect(members).toContain('memberLoadGate.isCurrent(requestEpoch)')
    expect(members).toContain('hasAssociationWriteContext')
    expect(members).toContain('v-if="hasAssociationContext && (item.canEdit || item.canReview)"')
    expect(attachments).toContain('hasAssociationWriteContext')
    expect(attachments).toContain('isAttachmentContentAvailable(item)')
    expect(attachments).toContain('isKnowledgeAttachmentSupported(item)')
    expect(attachments).toContain('v-if="isAttachmentContentAvailable(item)"')
  })
})
