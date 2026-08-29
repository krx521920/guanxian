package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EcosystemWorkflowStore {
    MatchInvitationView createInvitation(
            UUID matchId, UUID associationId, UUID senderEnterpriseId,
            MatchInvitationRequest request, ActorScope actor);

    List<MatchInvitationView> invitations(UUID matchId, ActorScope actor);

    Optional<MatchInvitationView> findInvitation(UUID invitationId, ActorScope actor);

    void expirePendingInvitations(UUID matchId);

    boolean hasPendingInvitation(UUID matchId);

    Optional<MatchInvitationView> respondInvitation(
            UUID invitationId, long expectedVersion, boolean accepted, String comment, ActorScope actor);

    NegotiationView addNegotiation(
            UUID matchId, UUID associationId, UUID enterpriseId,
            NegotiationRequest request, ActorScope actor);

    List<NegotiationView> negotiations(UUID matchId, ActorScope actor);

    Optional<NegotiationView> latestNegotiation(UUID matchId, ActorScope actor);

    MatchFeedbackView upsertFeedback(
            UUID matchId, UUID enterpriseId, MatchFeedbackRequest request, ActorScope actor);

    List<MatchFeedbackView> feedback(UUID matchId, ActorScope actor);

    OutcomeArchiveView archive(
            UUID matchId, UUID associationId, OutcomeArchiveRequest request, ActorScope actor);

    List<OutcomeArchiveView> outcomes(UUID matchId, ActorScope actor);
}
