import type {
  MatchAction,
  MatchFeedback,
  MatchInvitation,
  MatchNegotiation,
  MatchNegotiationStage,
  MatchOutcome,
  PersistedMatch,
  SessionUser,
} from '../types/domain'

export interface MatchWorkflowReaders {
  matchInvitations(matchId: string): Promise<MatchInvitation[]>
  matchNegotiations(matchId: string): Promise<MatchNegotiation[]>
  matchFeedback(matchId: string): Promise<MatchFeedback[]>
  matchOutcomes(matchId: string): Promise<MatchOutcome[]>
}

export interface MatchWorkflowSettled {
  invitations: PromiseSettledResult<MatchInvitation[]>
  negotiations: PromiseSettledResult<MatchNegotiation[]>
  feedback: PromiseSettledResult<MatchFeedback[]>
  outcomes: PromiseSettledResult<MatchOutcome[]>
}

export interface ParticipantWorkflowAccess {
  invitations: boolean
  negotiations: boolean
  feedback: boolean
}

export interface NegotiationStageOption {
  value: MatchNegotiationStage
  label: string
}

const orderedNegotiationStages: readonly NegotiationStageOption[] = [
  { value: 'INITIAL_CONTACT', label: '初次联系' },
  { value: 'TECHNICAL_EXCHANGE', label: '技术交流' },
  { value: 'COMMERCIAL_NEGOTIATION', label: '商务洽谈' },
  { value: 'CONTRACTING', label: '合同推进' },
  { value: 'CONTRACT_SIGNED', label: '合同已签署' },
]

export function hasMatchAction(item: PersistedMatch, action: MatchAction): boolean {
  return Array.isArray(item.allowedActions) && item.allowedActions.includes(action)
}

export function refreshedMatchOrNull(
  items: readonly PersistedMatch[],
  selectedId: string,
): PersistedMatch | null {
  return items.find((item) => item.id === selectedId) || null
}

export function canOpenMatchCollaboration(
  item: PersistedMatch,
  user: SessionUser | null | undefined,
  access: ParticipantWorkflowAccess,
): boolean {
  const directParticipant = Boolean(user?.enterpriseId
    && [item.demandEnterpriseId, item.candidateEnterpriseId].includes(user.enterpriseId))
  if (directParticipant) return true
  const associationContext = ['ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(user?.role || '')
    || (user?.role === 'SYSTEM_ADMIN' && !user.enterpriseId)
  return associationContext && access.invitations && access.negotiations && access.feedback
}

export function latestNegotiation(records: readonly MatchNegotiation[]): MatchNegotiation | null {
  return records.reduce<MatchNegotiation | null>((latest, current) => {
    if (!latest) return current
    const currentTime = Date.parse(current.createdAt)
    const latestTime = Date.parse(latest.createdAt)
    if (currentTime !== latestTime) return currentTime > latestTime ? current : latest
    return current.id > latest.id ? current : latest
  }, null)
}

export function availableNegotiationStages(records: readonly MatchNegotiation[]): NegotiationStageOption[] {
  const previous = latestNegotiation(records)?.stage as MatchNegotiationStage | undefined
  if (previous === 'TERMINATED' || previous === 'CONTRACT_SIGNED') return []
  if (!previous) return [orderedNegotiationStages[0], { value: 'TERMINATED', label: '终止洽谈' }]

  const index = orderedNegotiationStages.findIndex((option) => option.value === previous)
  if (index < 0) return []
  const allowed = [orderedNegotiationStages[index]]
  if (index + 1 < orderedNegotiationStages.length) allowed.push(orderedNegotiationStages[index + 1])
  return [...allowed, { value: 'TERMINATED', label: '终止洽谈' }]
}

export function canRespondToInvitation(
  invitation: MatchInvitation,
  user: SessionUser | null | undefined,
  now = Date.now(),
): boolean {
  return isInvitationResponder(invitation, user)
    && invitation.status === 'PENDING'
    && (!invitation.expiresAt || Date.parse(invitation.expiresAt) > now)
}

export function isInvitationResponder(
  invitation: MatchInvitation,
  user: SessionUser | null | undefined,
): boolean {
  const canWrite = user?.role === 'ENTERPRISE_ADMIN' || user?.role === 'SYSTEM_ADMIN'
  return Boolean(
    canWrite
      && user?.enterpriseId
      && user.enterpriseId === invitation.recipientEnterpriseId,
  )
}

export function isFutureLocalDateTime(value: string, now = Date.now()): boolean {
  if (!value) return true
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) && timestamp > now
}

export function isValidInvitationResponse(accepted: boolean, comment: string): boolean {
  return accepted || Boolean(comment.trim())
}

export function feedbackOutcomeForState(
  existingOutcome: string | null | undefined,
  matchState: string | null | undefined,
): 'SUCCESS' | 'NO_DEAL' | 'WITHDRAWN' {
  if (matchState === 'OUTCOME_PENDING') return 'SUCCESS'
  return existingOutcome === 'WITHDRAWN' ? 'WITHDRAWN' : 'NO_DEAL'
}

export async function loadMatchWorkflowSections(
  readers: MatchWorkflowReaders,
  matchId: string,
): Promise<MatchWorkflowSettled> {
  const [invitations, negotiations, feedback, outcomes] = await Promise.allSettled([
    readers.matchInvitations(matchId),
    readers.matchNegotiations(matchId),
    readers.matchFeedback(matchId),
    readers.matchOutcomes(matchId),
  ])
  return { invitations, negotiations, feedback, outcomes }
}
