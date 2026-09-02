import { describe, expect, it } from 'vitest'
import type { MatchInvitation, MatchNegotiation, PersistedMatch, SessionUser } from '../types/domain'
import {
  availableNegotiationStages,
  canRespondToInvitation,
  canOpenMatchCollaboration,
  feedbackOutcomeForState,
  hasMatchAction,
  isFutureLocalDateTime,
  isInvitationResponder,
  isValidInvitationResponse,
  loadMatchWorkflowSections,
  refreshedMatchOrNull,
} from './match-workflow'

describe('匹配闭环前端合同', () => {
  it('只按服务端下发的 allowedActions 展示单条写操作', () => {
    const item = { allowedActions: ['CONFIRM', 'CLOSE'] } as PersistedMatch
    expect(hasMatchAction(item, 'CONFIRM')).toBe(true)
    expect(hasMatchAction(item, 'INVITE')).toBe(false)
    expect(hasMatchAction({ allowedActions: [] } as unknown as PersistedMatch, 'CLOSE')).toBe(false)
  })

  it('洽谈必须从初次联系开始，并且只能停留或前进一个阶段', () => {
    expect(availableNegotiationStages([]).map(({ value }) => value)).toEqual(['INITIAL_CONTACT', 'TERMINATED'])
    const technical = [{ stage: 'TECHNICAL_EXCHANGE', createdAt: '2026-08-30T02:00:00Z' }] as MatchNegotiation[]
    expect(availableNegotiationStages(technical).map(({ value }) => value)).toEqual([
      'TECHNICAL_EXCHANGE', 'COMMERCIAL_NEGOTIATION', 'TERMINATED',
    ])
    expect(availableNegotiationStages([
      { stage: 'COMMERCIAL_NEGOTIATION', createdAt: '2026-08-30T01:00:00Z' },
      { stage: 'CONTRACT_SIGNED', createdAt: '2026-08-30T03:00:00Z' },
    ] as MatchNegotiation[])).toEqual([])
    const tied = [
      { id: '00000000-0000-0000-0000-000000000001', stage: 'INITIAL_CONTACT', createdAt: '2026-08-30T04:00:00Z' },
      { id: '00000000-0000-0000-0000-000000000002', stage: 'TECHNICAL_EXCHANGE', createdAt: '2026-08-30T04:00:00Z' },
    ] as MatchNegotiation[]
    expect(availableNegotiationStages(tied).map(({ value }) => value)).toEqual([
      'TECHNICAL_EXCHANGE', 'COMMERCIAL_NEGOTIATION', 'TERMINATED',
    ])
  })

  it('只有被邀请企业的可写身份能在截止前应答', () => {
    const invitation = {
      recipientEnterpriseId: 'enterprise-2', status: 'PENDING', expiresAt: '2026-09-01T00:00:00Z',
    } as MatchInvitation
    const enterpriseAdmin = {
      role: 'ENTERPRISE_ADMIN', enterpriseId: 'enterprise-2',
    } as SessionUser
    expect(canRespondToInvitation(invitation, enterpriseAdmin, Date.parse('2026-08-31T00:00:00Z'))).toBe(true)
    expect(canRespondToInvitation(invitation, { ...enterpriseAdmin, role: 'ENTERPRISE_MEMBER' })).toBe(false)
    expect(canRespondToInvitation(invitation, { ...enterpriseAdmin, enterpriseId: 'enterprise-1' })).toBe(false)
    expect(canRespondToInvitation(invitation, enterpriseAdmin, Date.parse('2026-09-02T00:00:00Z'))).toBe(false)
    expect(canRespondToInvitation({ ...invitation, expiresAt: 'invalid' }, enterpriseAdmin)).toBe(false)
    expect(isInvitationResponder(invitation, enterpriseAdmin)).toBe(true)
    expect(isInvitationResponder(invitation, { ...enterpriseAdmin, role: 'ENTERPRISE_MEMBER' })).toBe(false)
    expect(canRespondToInvitation({ ...invitation, status: 'EXPIRED' }, enterpriseAdmin)).toBe(false)
  })

  it('邀请截止时间为空或在未来才有效', () => {
    const now = Date.parse('2026-08-30T00:00:00Z')
    expect(isFutureLocalDateTime('', now)).toBe(true)
    expect(isFutureLocalDateTime('2026-08-30T01:00:00Z', now)).toBe(true)
    expect(isFutureLocalDateTime('2026-08-29T23:59:59Z', now)).toBe(false)
    expect(isFutureLocalDateTime('not-a-date', now)).toBe(false)
  })

  it('接受邀请可不写说明，拒绝邀请必须填写原因', () => {
    expect(isValidInvitationResponse(true, '')).toBe(true)
    expect(isValidInvitationResponse(false, '  ')).toBe(false)
    expect(isValidInvitationResponse(false, '暂不符合交付计划')).toBe(true)
  })

  it('反馈结果始终收敛到当前匹配状态允许的选项', () => {
    expect(feedbackOutcomeForState('SUCCESS', 'CLOSED')).toBe('NO_DEAL')
    expect(feedbackOutcomeForState('WITHDRAWN', 'CLOSED')).toBe('WITHDRAWN')
    expect(feedbackOutcomeForState('NO_DEAL', 'OUTCOME_PENDING')).toBe('SUCCESS')
  })

  it('一个工作流子模块不可读时仍保留其他已授权结果', async () => {
    const outcome = { id: 'outcome-1', title: '已授权成果' }
    const settled = await loadMatchWorkflowSections({
      matchInvitations: async () => { throw new Error('not visible') },
      matchNegotiations: async () => [],
      matchFeedback: async () => { throw new Error('not visible') },
      matchOutcomes: async () => [outcome] as never[],
    }, 'match-1')
    expect(settled.invitations.status).toBe('rejected')
    expect(settled.feedback.status).toBe('rejected')
    expect(settled.outcomes.status).toBe('fulfilled')
    if (settled.outcomes.status === 'fulfilled') expect(settled.outcomes.value[0]?.title).toBe('已授权成果')
  })

  it('刷新后记录不再可见时安全关闭详情，不保留越权旧快照', () => {
    const visible = { id: 'match-visible' } as PersistedMatch
    expect(refreshedMatchOrNull([visible], 'match-visible')).toBe(visible)
    expect(refreshedMatchOrNull([visible], 'match-hidden')).toBeNull()
  })

  it('只有参与企业或可读参与方工作流的所属协会能进入协作事项', () => {
    const item = { demandEnterpriseId: 'enterprise-1', candidateEnterpriseId: 'enterprise-2' } as PersistedMatch
    const participant = { role: 'ENTERPRISE_MEMBER', enterpriseId: 'enterprise-2' } as SessionUser
    const partner = { role: 'ENTERPRISE_MEMBER', enterpriseId: 'enterprise-partner' } as SessionUser
    const association = { role: 'ASSOCIATION_ADMIN' } as SessionUser
    const outcomeOnly = { invitations: false, negotiations: false, feedback: false }
    expect(canOpenMatchCollaboration(item, participant, outcomeOnly)).toBe(true)
    expect(canOpenMatchCollaboration(item, partner, outcomeOnly)).toBe(false)
    expect(canOpenMatchCollaboration(item, association, outcomeOnly)).toBe(false)
    expect(canOpenMatchCollaboration(item, association, { invitations: true, negotiations: true, feedback: true })).toBe(true)
  })
})
