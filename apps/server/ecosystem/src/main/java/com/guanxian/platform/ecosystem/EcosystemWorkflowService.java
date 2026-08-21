package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EcosystemWorkflowService {
    private final EcosystemMatchStore matchStore;
    private final EcosystemWorkflowStore workflowStore;
    private final EcosystemCatalogStore catalogStore;

    public EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore) {
        this.matchStore = matchStore;
        this.workflowStore = workflowStore;
        this.catalogStore = catalogStore;
    }

    @Transactional
    public MatchInvitationView invite(
            UUID matchId, MatchInvitationRequest request, ActorScope actor) {
        PersistedMatchView match = match(matchId, actor);
        requireDemandOwnerOrAssociation(match, actor);
        if (!request.recipientEnterpriseId().equals(match.candidateEnterpriseId())) {
            throw new PreconditionFailedException(
                    "invitation recipient must be the candidate enterprise of this match");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new PreconditionFailedException("invitation expiry must be in the future");
        }
        MatchInvitationView created = workflowStore.createInvitation(
                match.id(), actor.associationId(), actor.enterpriseId(), request, actor);
        record(actor, "CREATE_INVITATION", "MATCH_INVITATION", created.id(),
                match.demandEnterpriseId(), created.version(), created);
        return created;
    }

    @Transactional(readOnly = true)
    public List<MatchInvitationView> invitations(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.invitations(matchId, actor);
    }

    @Transactional
    public MatchInvitationView respond(
            UUID invitationId,
            long expectedVersion,
            MatchInvitationResponse response,
            ActorScope actor) {
        MatchInvitationView invitation = workflowStore.findInvitation(invitationId, actor)
                .orElseThrow(() -> new NotFoundException("match invitation", invitationId));
        PersistedMatchView match = match(invitation.matchId(), actor);
        if (actor.enterpriseId() == null
                || !actor.enterpriseId().equals(invitation.recipientEnterpriseId())) {
            throw new ForbiddenException(
                    "INVITATION_RECIPIENT_REQUIRED", "only the recipient enterprise can respond");
        }
        if (!"PENDING".equals(invitation.status())) {
            throw new PreconditionFailedException("invitation has already been resolved");
        }
        if (invitation.expiresAt() != null && !invitation.expiresAt().isAfter(Instant.now())) {
            throw new PreconditionFailedException("invitation has expired");
        }
        if (invitation.version() != expectedVersion) {
            throw stale("invitation");
        }
        MatchInvitationView updated = workflowStore.respondInvitation(
                        invitationId, expectedVersion, response.accepted(), response.comment(), actor)
                .orElseThrow(() -> stale("invitation"));
        record(actor, response.accepted() ? "ACCEPT_INVITATION" : "REJECT_INVITATION",
                "MATCH_INVITATION", updated.id(), match.demandEnterpriseId(), updated.version(), updated);
        return updated;
    }

    @Transactional
    public NegotiationView addNegotiation(
            UUID matchId, NegotiationRequest request, ActorScope actor) {
        PersistedMatchView match = match(matchId, actor);
        requireParticipantOrAssociation(match, actor);
        NegotiationView created = workflowStore.addNegotiation(
                matchId, actor.associationId(), actor.enterpriseId(), request, actor);
        record(actor, "ADD_NEGOTIATION", "NEGOTIATION_RECORD", created.id(),
                match.demandEnterpriseId(), 0, created);
        return created;
    }

    @Transactional(readOnly = true)
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.negotiations(matchId, actor);
    }

    @Transactional
    public MatchFeedbackView feedback(
            UUID matchId, MatchFeedbackRequest request, ActorScope actor) {
        PersistedMatchView match = match(matchId, actor);
        if (actor.enterpriseId() == null
                || (!actor.enterpriseId().equals(match.demandEnterpriseId())
                && !actor.enterpriseId().equals(match.candidateEnterpriseId()))) {
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED", "only a participating enterprise can submit feedback");
        }
        MatchFeedbackView value = workflowStore.upsertFeedback(
                matchId, actor.enterpriseId(), request, actor);
        record(actor, "UPSERT_FEEDBACK", "MATCH_FEEDBACK", value.id(),
                actor.enterpriseId(), 0, value);
        return value;
    }

    @Transactional
    public OutcomeArchiveView archive(
            UUID matchId, OutcomeArchiveRequest request, ActorScope actor) {
        PersistedMatchView match = match(matchId, actor);
        requireDemandOwnerOrAssociation(match, actor);
        if (!Set.of("CONFIRMED", "CLOSED").contains(match.state())) {
            throw new PreconditionFailedException(
                    "only a confirmed or closed match can be archived as an outcome");
        }
        OutcomeArchiveView value = workflowStore.archive(
                matchId, actor.associationId(), request, actor);
        record(actor, "ARCHIVE_OUTCOME", "OUTCOME_ARCHIVE", value.id(),
                match.demandEnterpriseId(), value.version(), value);
        return value;
    }

    @Transactional(readOnly = true)
    public List<OutcomeArchiveView> outcomes(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.outcomes(matchId, actor);
    }

    private PersistedMatchView match(UUID id, ActorScope actor) {
        return matchStore.find(id, actor).orElseThrow(() -> new NotFoundException("ecosystem match", id));
    }

    private static void requireDemandOwnerOrAssociation(
            PersistedMatchView match, ActorScope actor) {
        if (actor.isSystemAdmin() || actor.isAssociationStaff()
                || match.demandEnterpriseId().equals(actor.enterpriseId())) {
            return;
        }
        throw new ForbiddenException(
                "MATCH_OWNER_REQUIRED", "only the demand owner or association can perform this operation");
    }

    private static void requireParticipantOrAssociation(
            PersistedMatchView match, ActorScope actor) {
        if (actor.isSystemAdmin() || actor.isAssociationStaff()
                || match.demandEnterpriseId().equals(actor.enterpriseId())
                || match.candidateEnterpriseId().equals(actor.enterpriseId())) {
            return;
        }
        throw new ForbiddenException(
                "MATCH_PARTICIPANT_REQUIRED", "only a match participant or association can perform this operation");
    }

    private void record(
            ActorScope actor,
            String action,
            String type,
            UUID id,
            UUID enterpriseId,
            long version,
            Object snapshot) {
        catalogStore.recordChange(
                actor, action, type, id, actor.associationId(), enterpriseId, version, snapshot);
    }

    private static PreconditionFailedException stale(String resource) {
        return new PreconditionFailedException(
                resource + " version is stale; reload and retry with the latest ETag");
    }
}
