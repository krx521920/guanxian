package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import com.guanxian.platform.shared.notification.BusinessNotification;
import com.guanxian.platform.shared.notification.BusinessNotificationPublisher;
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
    private final PartnerFieldAuthorization partnerFields;
    private final BusinessNotificationPublisher notifications;

    @Autowired
    public EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle,
            PartnerFieldAuthorization partnerFields,
            BusinessNotificationPublisher notifications) {
        this.matchStore = matchStore;
        this.workflowStore = workflowStore;
        this.catalogStore = catalogStore;
        this.enterpriseLifecycle = enterpriseLifecycle;
        this.partnerFields = partnerFields;
        this.notifications = notifications;
    }

    public EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle,
            PartnerFieldAuthorization partnerFields) {
        this(matchStore, workflowStore, catalogStore, enterpriseLifecycle, partnerFields, (event, actor) -> 0);
    }

    public EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle) {
        this(matchStore, workflowStore, catalogStore, enterpriseLifecycle,
                PartnerFieldAuthorization.allowAll());
    }

    EcosystemWorkflowService(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            EcosystemCatalogStore catalogStore) {
        this(matchStore, workflowStore, catalogStore, enterpriseId -> true,
                PartnerFieldAuthorization.allowAll());
    }

    @Transactional
    public MatchInvitationView invite(
            UUID matchId, long expectedMatchVersion,
            MatchInvitationRequest request, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore,
                () -> inviteInternal(matchId, expectedMatchVersion, request, actor));
    }

    private MatchInvitationView inviteInternal(
            UUID matchId, long expectedMatchVersion,
            MatchInvitationRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        requireDemandOwnerOrAssociation(match, actor);
        requireVersion(match, expectedMatchVersion);
        match = normalizeExpiredInvitation(match, actor);
        expectedMatchVersion = match.version();
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
        notifications.publish(new BusinessNotification(
                actor.associationId(), List.of(created.recipientEnterpriseId()), true,
                "MATCH_INVITATION", "收到新的生态协作邀请", created.message(),
                "MATCH_INVITATION", created.id(), created.version(),
                "match-invitation:" + created.id() + ":" + created.version()), actor);
        return redactInvitationIdentity(created);
    }

    @Transactional
    public List<MatchInvitationView> invitations(UUID matchId, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore, () -> invitationsInternal(matchId, actor));
    }

    private List<MatchInvitationView> invitationsInternal(UUID matchId, ActorScope actor) {
        normalizeExpiredInvitation(match(matchId, actor), actor);
        return workflowStore.invitations(matchId, actor).stream()
                .map(EcosystemWorkflowService::redactInvitationIdentity)
                .toList();
    }

    @Transactional
    public MatchInvitationView respond(
            UUID invitationId,
            long expectedVersion,
            MatchInvitationResponse response,
            ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore,
                () -> respondInternal(invitationId, expectedVersion, response, actor));
    }

    private MatchInvitationView respondInternal(
            UUID invitationId,
            long expectedVersion,
            MatchInvitationResponse response,
            ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        MatchInvitationView invitation = workflowStore.findInvitation(invitationId, actor)
                .orElseThrow(() -> new NotFoundException("match invitation", invitationId));
        PersistedMatchView match = normalizeExpiredInvitation(
                matchForWrite(invitation.matchId(), actor), actor);
        invitation = workflowStore.findInvitation(invitationId, actor)
                .orElseThrow(() -> new NotFoundException("match invitation", invitationId));
        if (actor.enterpriseId() == null
                || !actor.enterpriseId().equals(invitation.recipientEnterpriseId())) {
            throw new ForbiddenException(
                    "INVITATION_RECIPIENT_REQUIRED", "only the recipient enterprise can respond");
        }
        if (!"PENDING".equals(invitation.status())) {
            throw new PreconditionFailedException("invitation has already been resolved");
        }
        requireEnterpriseWriter(actor);
        if (invitation.expiresAt() != null && !invitation.expiresAt().isAfter(Instant.now())) {
            throw new PreconditionFailedException("invitation has expired");
        }
        MatchLifecycle.requireInvitationResponseAllowed(match);
        if (!response.accepted()
                && (response.comment() == null || response.comment().isBlank())) {
            throw new PreconditionFailedException(
                    "a rejection comment is required as the match close reason");
        }
        if (invitation.version() != expectedVersion) {
            throw stale("invitation");
        }
        MatchInvitationView updated = workflowStore.respondInvitation(
                        invitationId, expectedVersion, response.accepted(), response.comment(), actor)
                .orElseThrow(() -> stale("invitation"));
        String targetState = response.accepted()
                ? MatchLifecycle.NEGOTIATING : MatchLifecycle.CLOSED;
        String closeReason = response.accepted() ? null : response.comment().trim();
        transition(match, match.version(), targetState, closeReason,
                response.accepted() ? "ACCEPT_INVITATION" : "REJECT_INVITATION", actor);
        record(actor, response.accepted() ? "ACCEPT_INVITATION" : "REJECT_INVITATION",
                "MATCH_INVITATION", updated.id(), match.demandEnterpriseId(), updated.version(), updated);
        return redactInvitationIdentity(updated);
    }

    @Transactional
    public NegotiationView addNegotiation(
            UUID matchId, long expectedMatchVersion,
            NegotiationRequest request, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore,
                () -> addNegotiationInternal(matchId, expectedMatchVersion, request, actor));
    }

    private NegotiationView addNegotiationInternal(
            UUID matchId, long expectedMatchVersion,
            NegotiationRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        requireParticipantOrAssociation(match, actor);
        if (actor.enterpriseId() != null) {
            requireEnterpriseWriter(actor);
        }
        MatchLifecycle.requireNegotiationAllowed(match);
        requireVersion(match, expectedMatchVersion);
        String stage = request.stage().trim();
        String previousStage = workflowStore.latestNegotiation(matchId, actor)
                .map(NegotiationView::stage).orElse(null);
        MatchLifecycle.requireNextNegotiationStage(previousStage, stage);
        String normalizedSummary = request.summary().trim();
        if (MatchLifecycle.TERMINATED.equals(stage) && normalizedSummary.length() > 1000) {
            throw new PreconditionFailedException(
                    "a terminated negotiation summary cannot exceed 1000 characters");
        }
        NegotiationView created = workflowStore.addNegotiation(
                matchId, actor.associationId(), actor.enterpriseId(), request, actor);
        String targetState = MatchLifecycle.CONTRACT_SIGNED.equals(stage)
                ? MatchLifecycle.OUTCOME_PENDING
                : MatchLifecycle.TERMINATED.equals(stage)
                ? MatchLifecycle.CLOSED : MatchLifecycle.NEGOTIATING;
        String closeReason = MatchLifecycle.TERMINATED.equals(stage)
                ? normalizedSummary : null;
        if (MatchLifecycle.CLOSED.equals(targetState)) {
            cancelPendingInvitations(match, closeReason, actor);
        }
        transition(match, expectedMatchVersion, targetState, closeReason,
                "ADVANCE_NEGOTIATION", actor);
        record(actor, "ADD_NEGOTIATION", "NEGOTIATION_RECORD", created.id(),
                match.demandEnterpriseId(), created.version(), created);
        notifications.publish(new BusinessNotification(
                actor.associationId(), List.of(match.demandEnterpriseId(), match.candidateEnterpriseId()), true,
                "MATCH_NEGOTIATION", "生态洽谈进度更新", created.stage() + "：" + created.summary(),
                "NEGOTIATION_RECORD", created.id(), created.version(),
                "match-negotiation:" + created.id() + ":" + created.version()), actor);
        return redactNegotiationIdentity(created);
    }

    @Transactional(readOnly = true)
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore, () -> negotiationsInternal(matchId, actor));
    }

    private List<NegotiationView> negotiationsInternal(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.negotiations(matchId, actor).stream()
                .map(EcosystemWorkflowService::redactNegotiationIdentity)
                .toList();
    }

    @Transactional
    public MatchFeedbackView feedback(
            UUID matchId, Long expectedVersion,
            MatchFeedbackRequest request, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore,
                () -> feedbackInternal(matchId, expectedVersion, request, actor));
    }

    private MatchFeedbackView feedbackInternal(
            UUID matchId, Long expectedVersion,
            MatchFeedbackRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView match = matchForWrite(matchId, actor);
        if (actor.enterpriseId() == null
                || (!actor.enterpriseId().equals(match.demandEnterpriseId())
                && !actor.enterpriseId().equals(match.candidateEnterpriseId()))) {
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED", "only a participating enterprise can submit feedback");
        }
        requireEnterpriseWriter(actor);
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
        if ("SUCCESS".equals(outcome)
                && request.closeReason() != null && !request.closeReason().isBlank()) {
            throw new PreconditionFailedException(
                    "successful feedback must not contain a closeReason");
        }
        MatchFeedbackView existing = workflowStore.feedbackByEnterprise(
                matchId, actor.enterpriseId(), actor).orElse(null);
        if (existing == null && expectedVersion != null && expectedVersion != 0) {
            throw stale("feedback");
        }
        if (existing != null && expectedVersion == null) {
            throw new PreconditionRequiredException(
                    "If-Match is required when replacing existing match feedback");
        }
        if (existing != null && existing.version() != expectedVersion) {
            throw stale("feedback");
        }
        MatchFeedbackView value = workflowStore.upsertFeedback(
                        matchId, actor.enterpriseId(), expectedVersion, request, actor)
                .orElseThrow(() -> stale("feedback"));
        record(actor, "UPSERT_FEEDBACK", "MATCH_FEEDBACK", value.id(),
                actor.enterpriseId(), value.version(), value);
        return redactFeedbackIdentity(value);
    }

    public MatchFeedbackView feedback(
            UUID matchId, MatchFeedbackRequest request, ActorScope actor) {
        return feedback(matchId, null, request, actor);
    }

    @Transactional(readOnly = true)
    public List<MatchFeedbackView> feedback(UUID matchId, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore, () -> feedbackInternal(matchId, actor));
    }

    private List<MatchFeedbackView> feedbackInternal(UUID matchId, ActorScope actor) {
        match(matchId, actor);
        return workflowStore.feedback(matchId, actor).stream()
                .map(EcosystemWorkflowService::redactFeedbackIdentity)
                .toList();
    }

    @Transactional
    public OutcomeArchiveView archive(
            UUID matchId, long expectedMatchVersion,
            OutcomeArchiveRequest request, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore,
                () -> archiveInternal(matchId, expectedMatchVersion, request, actor));
    }

    private OutcomeArchiveView archiveInternal(
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
        if (workflowStore.hasActiveOutcome(matchId, actor)) {
            throw new PreconditionFailedException("this match already has an archived outcome");
        }
        OutcomeArchiveView value = workflowStore.archive(
                matchId, actor.associationId(), request, actor);
        transition(match, expectedMatchVersion, MatchLifecycle.ARCHIVED, null,
                "ARCHIVE_OUTCOME", actor);
        record(actor, "ARCHIVE_OUTCOME", "OUTCOME_ARCHIVE", value.id(),
                match.demandEnterpriseId(), value.version(), value);
        return redactOutcomeIdentity(value);
    }

    @Transactional(readOnly = true)
    public List<OutcomeArchiveView> outcomes(UUID matchId, ActorScope actor) {
        return InMemoryEcosystemUnitOfWork.execute(
                matchStore, workflowStore, () -> outcomesInternal(matchId, actor));
    }

    private List<OutcomeArchiveView> outcomesInternal(UUID matchId, ActorScope actor) {
        PersistedMatchView match = matchStore.find(matchId, actor)
                .orElseThrow(() -> new NotFoundException("ecosystem match", matchId));
        if (MatchLifecycle.PENDING_CONFIRMATION.equals(match.state())
                && !canReadUnrecommended(match, actor)) {
            throw new NotFoundException("ecosystem match", matchId);
        }
        boolean fullScope = hasFullOutcomeScope(match, actor);
        boolean partnerScope = !fullScope && hasPartnerMatchAuthorization(match, actor);
        if (!fullScope && !partnerScope
                && !(actor.isSystemAdmin()
                && EcosystemScopeGuard.systemCanReadMatch(actor, match, catalogStore))) {
            throw new NotFoundException("ecosystem match", matchId);
        }
        return workflowStore.outcomes(matchId, actor).stream()
                .filter(value -> canReadOutcome(value, match, actor, fullScope, partnerScope))
                .map(value -> fullScope ? redactOutcomeIdentity(value) : redactOutcomeSensitive(value))
                .toList();
    }

    private boolean hasFullOutcomeScope(PersistedMatchView match, ActorScope actor) {
        boolean participantEnterprise = actor.enterpriseId() != null
                && (actor.enterpriseId().equals(match.demandEnterpriseId())
                || actor.enterpriseId().equals(match.candidateEnterpriseId()));
        boolean demandAssociation = actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId());
        return participantEnterprise || demandAssociation;
    }

    private boolean hasPartnerMatchAuthorization(
            PersistedMatchView match, ActorScope actor) {
        return partnerFields.authorizedFields(
                        actor, match.demandEnterpriseId(), "MATCH", match.id())
                .filter(fields -> fields.contains("outcomes")).isPresent()
                && partnerFields.authorizedFields(
                        actor, match.candidateEnterpriseId(), "MATCH", match.id())
                .filter(fields -> fields.contains("outcomes")).isPresent();
    }

    private boolean canReadOutcome(
            OutcomeArchiveView value,
            PersistedMatchView match,
            ActorScope actor,
            boolean fullScope,
            boolean partnerScope) {
        return switch (value.visibility()) {
            case "PRIVATE" -> actor.subject().equals(value.archivedBySubject());
            case "ENTERPRISES" -> actor.enterpriseId() != null
                    && (actor.enterpriseId().equals(match.demandEnterpriseId())
                    || actor.enterpriseId().equals(match.candidateEnterpriseId()));
            case "ASSOCIATION" -> actor.associationId() != null
                    && catalogStore.enterpriseBelongsToAssociation(
                    match.demandEnterpriseId(), actor.associationId());
            case "PARTNERS" -> fullScope || partnerScope;
            case "PUBLIC" -> fullScope || partnerScope || actor.isSystemAdmin();
            default -> false;
        };
    }

    private static MatchInvitationView redactInvitationIdentity(MatchInvitationView value) {
        return new MatchInvitationView(
                value.id(), value.matchId(), value.senderEnterpriseId(), value.recipientEnterpriseId(),
                value.invitationType(), value.status(), value.message(), value.responseComment(),
                null, null, value.expiresAt(), value.respondedAt(), value.version(),
                value.createdAt(), value.updatedAt());
    }

    private static NegotiationView redactNegotiationIdentity(NegotiationView value) {
        return new NegotiationView(
                value.id(), value.matchId(), value.enterpriseId(), value.stage(), value.summary(),
                value.nextAction(), value.nextActionAt(), null, value.createdAt(), value.version());
    }

    private static MatchFeedbackView redactFeedbackIdentity(MatchFeedbackView value) {
        return new MatchFeedbackView(
                value.id(), value.matchId(), value.enterpriseId(), value.rating(), value.outcome(),
                value.closeReason(), value.comment(), null, value.submittedAt(), value.version(),
                value.updatedAt());
    }

    private static OutcomeArchiveView redactOutcomeIdentity(OutcomeArchiveView value) {
        return new OutcomeArchiveView(
                value.id(), value.matchId(), value.title(), value.summary(), value.contractAmount(),
                value.resultType(), value.visibility(), null, value.archivedAt(), value.version());
    }

    private static OutcomeArchiveView redactOutcomeSensitive(OutcomeArchiveView value) {
        return new OutcomeArchiveView(
                value.id(), value.matchId(), value.title(), value.summary(), null,
                value.resultType(), value.visibility(), null,
                value.archivedAt(), value.version());
    }

    private PersistedMatchView match(UUID id, ActorScope actor) {
        PersistedMatchView value = matchStore.find(id, actor)
                .orElseThrow(() -> new NotFoundException("ecosystem match", id));
        if (MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                && !canReadUnrecommended(value, actor)) {
            throw new NotFoundException("ecosystem match", id);
        }
        boolean systemContext = EcosystemScopeGuard.systemCanReadMatch(actor, value, catalogStore);
        if (actor.isSystemAdmin() && systemContext) {
            registerMatchContext(value, actor);
            return value;
        }
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId());
        if (owningAssociation
                || value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId())) {
            registerMatchContext(value, actor);
            return value;
        }
        throw new NotFoundException("ecosystem match", id);
    }

    private boolean canReadUnrecommended(PersistedMatchView value, ActorScope actor) {
        if (actor.isSystemAdmin()
                && actor.associationId() == null
                && actor.enterpriseId() == null) {
            return true;
        }
        if (value.demandEnterpriseId().equals(actor.enterpriseId())) {
            return true;
        }
        return actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId())
                && (actor.isAssociationStaff() || actor.isSystemAdmin())
                && (!actor.isSystemAdmin() || actor.enterpriseId() == null
                || value.demandEnterpriseId().equals(actor.enterpriseId()));
    }

    private PersistedMatchView matchForWrite(UUID id, ActorScope actor) {
        PersistedMatchView value = match(id, actor);
        if (value.demandEnterpriseId().equals(value.candidateEnterpriseId())) {
            throw new PreconditionFailedException("a demand enterprise cannot be matched with itself");
        }
        if (catalogStore.isDemandDeleted(value.demandId())) {
            throw new PreconditionFailedException(
                    "the source demand is deleted; restore it before continuing the match workflow");
        }
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
            if (systemOwnsDemand(match, actor)) {
                return;
            }
        } else if (owningAssociation || match.demandEnterpriseId().equals(actor.enterpriseId())
                && isEnterpriseWriter(actor)) {
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
            boolean participantEnterprise = actor.enterpriseId() != null
                    && (match.demandEnterpriseId().equals(actor.enterpriseId())
                    || match.candidateEnterpriseId().equals(actor.enterpriseId()));
            if (participantEnterprise || systemOwnsDemand(match, actor)) {
                return;
            }
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED",
                    "selected system context cannot manage this match negotiation");
        }
        if (owningAssociation || isEnterpriseWriter(actor)
                && (match.demandEnterpriseId().equals(actor.enterpriseId())
                || match.candidateEnterpriseId().equals(actor.enterpriseId()))) {
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

    private boolean systemOwnsDemand(PersistedMatchView match, ActorScope actor) {
        return actor.isSystemAdmin()
                && actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId())
                && (actor.enterpriseId() == null
                || actor.enterpriseId().equals(match.demandEnterpriseId()));
    }

    private static boolean isEnterpriseWriter(ActorScope actor) {
        return actor.isEnterpriseAdmin() || actor.isAssociationStaff() || actor.isSystemAdmin();
    }

    private static void requireEnterpriseWriter(ActorScope actor) {
        if (!isEnterpriseWriter(actor)) {
            throw new ForbiddenException(
                    "ENTERPRISE_WRITE_REQUIRED", "an enterprise write identity is required");
        }
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
                && (match.demandEnterpriseId().equals(actor.enterpriseId())
                || catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId()))) {
            associationId = actor.associationId();
        }
        workflowStore.registerMatchContext(match, associationId);
    }

    private PersistedMatchView normalizeExpiredInvitation(
            PersistedMatchView match, ActorScope actor) {
        if (!MatchLifecycle.INVITED.equals(match.state())) {
            return match;
        }
        if (!canMaintainInvitation(match, actor)) {
            return match;
        }
        List<MatchInvitationView> expired = workflowStore.expirePendingInvitations(match.id(), actor);
        for (MatchInvitationView invitation : expired) {
            record(actor, "EXPIRE_INVITATION", "MATCH_INVITATION", invitation.id(),
                    match.demandEnterpriseId(), invitation.version(), invitation);
        }
        if (expired.isEmpty() || workflowStore.hasPendingInvitation(match.id(), actor)) {
            return match;
        }
        return transition(match, match.version(), MatchLifecycle.CONFIRMED,
                null, "EXPIRE_INVITATION", actor);
    }

    private boolean canMaintainInvitation(PersistedMatchView match, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            boolean participantEnterprise = actor.enterpriseId() != null
                    && (actor.enterpriseId().equals(match.demandEnterpriseId())
                    || actor.enterpriseId().equals(match.candidateEnterpriseId()));
            return participantEnterprise && EcosystemScopeGuard.systemCanReadMatch(
                    actor, match, catalogStore)
                    || systemOwnsDemand(match, actor);
        }
        return actor.isEnterpriseAdmin()
                && actor.enterpriseId() != null
                && (actor.enterpriseId().equals(match.demandEnterpriseId())
                || actor.enterpriseId().equals(match.candidateEnterpriseId()))
                || actor.isAssociationStaff() && actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId());
    }

    private void cancelPendingInvitations(
            PersistedMatchView match, String reason, ActorScope actor) {
        for (MatchInvitationView invitation :
                workflowStore.cancelPendingInvitations(match.id(), reason, actor)) {
            record(actor, "CANCEL_INVITATION", "MATCH_INVITATION", invitation.id(),
                    match.demandEnterpriseId(), invitation.version(), invitation);
        }
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
