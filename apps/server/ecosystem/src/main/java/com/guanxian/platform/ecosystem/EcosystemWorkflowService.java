package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EnterpriseLifecycle enterpriseLifecycle;

    @Autowired
    public EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle) {
        this.matchStore = matchStore;
        this.workflowStore = workflowStore;
        this.catalogStore = catalogStore;
        this.enterpriseLifecycle = enterpriseLifecycle;
    }

    EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore) {
        this(matchStore, workflowStore, catalogStore, enterpriseId -> true);
    }

    @Transactional
    public MatchInvitationView invite(
            UUID matchId, long expectedMatchVersion,
            MatchInvitationRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        requireDemandOwnerOrAssociation(match, actor);
        requireVersion(match, expectedMatchVersion);
        workflowStore.expirePendingInvitations(match.id(), actor);
        if (MatchLifecycle.INVITED.equals(match.state())
                && !workflowStore.hasPendingInvitation(match.id(), actor)) {
            match = transition(match, expectedMatchVersion, MatchLifecycle.CONFIRMED,
                    null, "EXPIRE_INVITATION", actor);
            expectedMatchVersion = match.version();
        }
        MatchLifecycle.requireInvitationAllowed(match);
        if (!request.recipientEnterpriseId().equals(match.candidateEnterpriseId())) {
            throw new PreconditionFailedException(
                    "invitation recipient must be the candidate enterprise of this match");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new PreconditionFailedException("invitation expiry must be in the future");
        }
        boolean associationSender = actor.isAssociationStaff()
                || actor.isSystemAdmin() && actor.enterpriseId() == null;
        if (associationSender
                && !"ASSOCIATION_RECOMMENDATION".equals(request.invitationType())) {
            throw new PreconditionFailedException(
                    "association staff must send an ASSOCIATION_RECOMMENDATION invitation");
        }
        if (!associationSender
                && !"ENTERPRISE".equals(request.invitationType())) {
            throw new PreconditionFailedException(
                    "enterprise users must send an ENTERPRISE invitation");
        }
        if (workflowStore.hasPendingInvitation(match.id(), actor)) {
            throw new PreconditionFailedException("this match already has a pending invitation");
        }
        transition(match, expectedMatchVersion, MatchLifecycle.INVITED, null, "SEND_INVITATION", actor);
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
        EcosystemScopeGuard.requireWriteContext(actor);
        MatchInvitationView invitation = workflowStore.findInvitation(invitationId, actor)
                .orElseThrow(() -> new NotFoundException("match invitation", invitationId));
        PersistedMatchView match = matchForWrite(invitation.matchId(), actor);
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
        MatchLifecycle.requireInvitationResponseAllowed(match);
        if (invitation.version() != expectedVersion) {
            throw stale("invitation");
        }
        MatchInvitationView updated = workflowStore.respondInvitation(
                        invitationId, expectedVersion, response.accepted(), response.comment(), actor)
                .orElseThrow(() -> stale("invitation"));
        String targetState = response.accepted()
                ? MatchLifecycle.NEGOTIATING : MatchLifecycle.CLOSED;
        String closeReason = response.accepted() ? null
                : response.comment() == null || response.comment().isBlank()
                ? "candidate enterprise rejected the invitation" : response.comment().trim();
        transition(match, match.version(), targetState, closeReason,
                response.accepted() ? "ACCEPT_INVITATION" : "REJECT_INVITATION", actor);
        record(actor, response.accepted() ? "ACCEPT_INVITATION" : "REJECT_INVITATION",
                "MATCH_INVITATION", updated.id(), match.demandEnterpriseId(), updated.version(), updated);
        return updated;
    }

    @Transactional
    public NegotiationView addNegotiation(
            UUID matchId, long expectedMatchVersion,
            NegotiationRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        requireParticipantOrAssociation(match, actor);
        if (actor.enterpriseId() == null) {
            throw new ForbiddenException(
                    "ENTERPRISE_CONTEXT_REQUIRED",
                    "a participating enterprise context is required to add a negotiation record");
        }
        MatchLifecycle.requireNegotiationAllowed(match);
        requireVersion(match, expectedMatchVersion);
        String stage = request.stage().trim();
        String previousStage = workflowStore.latestNegotiation(matchId, actor)
                .map(NegotiationView::stage).orElse(null);
        MatchLifecycle.requireNextNegotiationStage(previousStage, stage);
        NegotiationView created = workflowStore.addNegotiation(
                matchId, actor.associationId(), actor.enterpriseId(), request, actor);
        String targetState = MatchLifecycle.CONTRACT_SIGNED.equals(stage)
                ? MatchLifecycle.OUTCOME_PENDING
                : MatchLifecycle.TERMINATED.equals(stage)
                ? MatchLifecycle.CLOSED : MatchLifecycle.NEGOTIATING;
        String closeReason = MatchLifecycle.TERMINATED.equals(stage)
                ? request.summary().trim() : null;
        transition(match, expectedMatchVersion, targetState, closeReason,
                "ADVANCE_NEGOTIATION", actor);
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
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        if (actor.enterpriseId() == null
                || (!actor.enterpriseId().equals(match.demandEnterpriseId())
                && !actor.enterpriseId().equals(match.candidateEnterpriseId()))) {
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED", "only a participating enterprise can submit feedback");
        }
        MatchLifecycle.requireFeedbackAllowed(match);
        String outcome = request.outcome().trim();
        if (MatchLifecycle.OUTCOME_PENDING.equals(match.state()) && !"SUCCESS".equals(outcome)) {
            throw new PreconditionFailedException(
                    "an outcome-pending match only accepts SUCCESS feedback");
        }
        if (MatchLifecycle.CLOSED.equals(match.state()) && "SUCCESS".equals(outcome)) {
            throw new PreconditionFailedException("a closed match cannot receive SUCCESS feedback");
        }
        if (!"SUCCESS".equals(outcome)
                && (request.closeReason() == null || request.closeReason().isBlank())) {
            throw new PreconditionFailedException(
                    "closeReason is required for unsuccessful feedback");
        }
        MatchFeedbackView value = workflowStore.upsertFeedback(
                matchId, actor.enterpriseId(), request, actor);
        record(actor, "UPSERT_FEEDBACK", "MATCH_FEEDBACK", value.id(),
                actor.enterpriseId(), 0, value);
        return value;
    }

    @Transactional(readOnly = true)
    public List<MatchFeedbackView> feedback(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.feedback(matchId, actor);
    }

    @Transactional
    public OutcomeArchiveView archive(
            UUID matchId, long expectedMatchVersion,
            OutcomeArchiveRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        requireDemandOwnerOrAssociation(match, actor);
        MatchLifecycle.requireOutcomeAllowed(match);
        requireVersion(match, expectedMatchVersion);
        Set<UUID> successfulFeedback = workflowStore.feedback(matchId, actor).stream()
                .filter(value -> "SUCCESS".equals(value.outcome()))
                .map(MatchFeedbackView::enterpriseId)
                .collect(java.util.stream.Collectors.toSet());
        if (!successfulFeedback.containsAll(Set.of(
                match.demandEnterpriseId(), match.candidateEnterpriseId()))) {
            throw new PreconditionFailedException(
                    "both participating enterprises must submit SUCCESS feedback before outcome archival");
        }
        if (!workflowStore.outcomes(matchId, actor).isEmpty()) {
            throw new PreconditionFailedException("this match already has an archived outcome");
        }
        OutcomeArchiveView value = workflowStore.archive(
                matchId, actor.associationId(), request, actor);
        transition(match, expectedMatchVersion, MatchLifecycle.ARCHIVED, null,
                "ARCHIVE_OUTCOME", actor);
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
        PersistedMatchView value = matchStore.find(id, actor)
                .orElseThrow(() -> new NotFoundException("ecosystem match", id));
        if (actor.isSystemAdmin() || actor.isAssociationStaff()) {
            registerMatchContext(value, actor);
            return value;
        }
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId());
        boolean systemContext = EcosystemScopeGuard.systemCanReadMatch(actor, value, catalogStore);
        if (systemContext || owningAssociation
                || value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId())) {
            registerMatchContext(value, actor);
            return value;
        }
        throw new NotFoundException("ecosystem match", id);
    }

    private PersistedMatchView matchForWrite(UUID id, ActorScope actor) {
        PersistedMatchView value = match(id, actor);
        if (!enterpriseLifecycle.isOperational(value.demandEnterpriseId())
                || !enterpriseLifecycle.isOperational(value.candidateEnterpriseId())) {
            throw new PreconditionFailedException(
                    "both enterprises must be active before participating in ecosystem workflows");
        }
        return value;
    }

    private void requireDemandOwnerOrAssociation(
            PersistedMatchView match, ActorScope actor) {
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId());
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemMatchWrite(actor, match, catalogStore);
            if (actor.enterpriseId() == null
                    || match.demandEnterpriseId().equals(actor.enterpriseId())) {
                return;
            }
        } else if (owningAssociation || match.demandEnterpriseId().equals(actor.enterpriseId())) {
            return;
        }
        throw new ForbiddenException(
                "MATCH_OWNER_REQUIRED", "only the demand owner or association can perform this operation");
    }

    private void requireParticipantOrAssociation(
            PersistedMatchView match, ActorScope actor) {
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId());
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemMatchWrite(actor, match, catalogStore);
            return;
        }
        if (owningAssociation || match.demandEnterpriseId().equals(actor.enterpriseId())
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

    private PersistedMatchView transition(
            PersistedMatchView current,
            long expectedVersion,
            String target,
            String reason,
            String action,
            ActorScope actor) {
        requireVersion(current, expectedVersion);
        PersistedMatchView updated = matchStore.transition(
                        current.id(), expectedVersion, target, reason, actor)
                .orElseThrow(() -> stale("match"));
        record(actor, action, "ECOSYSTEM_MATCH", updated.id(),
                updated.demandEnterpriseId(), updated.version(), updated);
        return updated;
    }

    private void registerMatchContext(PersistedMatchView match, ActorScope actor) {
        UUID associationId = null;
        if (actor.associationId() != null
                && (actor.isSystemAdmin() || actor.isAssociationStaff()
                || match.demandEnterpriseId().equals(actor.enterpriseId())
                || catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId()))) {
            associationId = actor.associationId();
        }
        workflowStore.registerMatchContext(match, associationId);
    }

    private static void requireVersion(PersistedMatchView match, long expectedVersion) {
        if (match.version() != expectedVersion) {
            throw stale("match");
        }
    }

    private static PreconditionFailedException stale(String resource) {
        return new PreconditionFailedException(
                resource + " version is stale; reload and retry with the latest ETag");
    }
}
