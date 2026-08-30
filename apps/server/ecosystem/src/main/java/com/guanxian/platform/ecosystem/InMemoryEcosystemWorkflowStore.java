package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryEcosystemWorkflowStore implements EcosystemWorkflowStore {
    private final ConcurrentMap<UUID, MatchInvitationView> invitations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, NegotiationView> negotiations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> latestNegotiationIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MatchFeedbackView> feedback = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, OutcomeArchiveView> outcomes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MatchContext> matchContexts = new ConcurrentHashMap<>();
    private final EnterpriseLifecycle enterpriseLifecycle;

    @Autowired
    InMemoryEcosystemWorkflowStore(EnterpriseLifecycle enterpriseLifecycle) {
        this.enterpriseLifecycle = enterpriseLifecycle;
    }

    InMemoryEcosystemWorkflowStore() {
        this(enterpriseId -> true);
    }

    @Override
    public void registerMatchContext(PersistedMatchView match, UUID associationId) {
        matchContexts.compute(match.id(), (ignored, existing) -> new MatchContext(
                associationId != null ? associationId : existing == null ? null : existing.associationId(),
                match.demandEnterpriseId(), match.candidateEnterpriseId()));
    }

    @Override
    public MatchInvitationView createInvitation(
            UUID matchId,
            UUID associationId,
            UUID senderEnterpriseId,
            MatchInvitationRequest request,
            ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireWrite(matchId, actor);
        requireSender(senderEnterpriseId, actor);
        MatchContext context = matchContexts.get(matchId);
        if (context == null
                || !request.recipientEnterpriseId().equals(context.candidateEnterpriseId())) {
            throw new ForbiddenException(
                    "MATCH_SCOPE_VIOLATION",
                    "invitation recipient must be the candidate enterprise of the match");
        }
        Instant now = Instant.now();
        MatchInvitationView value = new MatchInvitationView(
                UUID.randomUUID(), matchId, senderEnterpriseId, request.recipientEnterpriseId(),
                request.invitationType(), "PENDING", clean(request.message()), null,
                actor.subject(), null, request.expiresAt(), null, 0, now, now);
        invitations.put(value.id(), value);
        return value;
    }

    @Override
    public List<MatchInvitationView> invitations(UUID matchId, ActorScope actor) {
        return invitations.values().stream()
                .filter(value -> value.matchId().equals(matchId))
                .filter(value -> canRead(value.matchId(), actor))
                .sorted(Comparator.comparing(MatchInvitationView::createdAt).reversed()
                        .thenComparing(MatchInvitationView::id))
                .toList();
    }

    @Override
    public Optional<MatchInvitationView> findInvitation(UUID invitationId, ActorScope actor) {
        return Optional.ofNullable(invitations.get(invitationId))
                .filter(value -> canRead(value.matchId(), actor));
    }

    @Override
    public synchronized void expirePendingInvitations(UUID matchId, ActorScope actor) {
        requireWrite(matchId, actor);
        Instant now = Instant.now();
        invitations.replaceAll((id, value) -> value.matchId().equals(matchId)
                && "PENDING".equals(value.status())
                && value.expiresAt() != null
                && !value.expiresAt().isAfter(now)
                ? new MatchInvitationView(
                value.id(), value.matchId(), value.senderEnterpriseId(),
                value.recipientEnterpriseId(), value.invitationType(), "EXPIRED",
                value.message(), value.responseComment(), value.sentBySubject(),
                value.respondedBySubject(), value.expiresAt(), value.respondedAt(),
                value.version() + 1, value.createdAt(), now)
                : value);
    }

    @Override
    public boolean hasPendingInvitation(UUID matchId, ActorScope actor) {
        if (!canRead(matchId, actor)) {
            return false;
        }
        Instant now = Instant.now();
        return invitations.values().stream().anyMatch(value -> value.matchId().equals(matchId)
                && "PENDING".equals(value.status())
                && (value.expiresAt() == null || value.expiresAt().isAfter(now)));
    }

    @Override
    public synchronized Optional<MatchInvitationView> respondInvitation(
            UUID invitationId,
            long expectedVersion,
            boolean accepted,
            String comment,
            ActorScope actor) {
        MatchInvitationView old = invitations.get(invitationId);
        if (old == null || old.version() != expectedVersion || !"PENDING".equals(old.status())
                || !canWrite(old.matchId(), actor)
                || actor.enterpriseId() == null
                || !actor.enterpriseId().equals(old.recipientEnterpriseId())) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        MatchInvitationView updated = new MatchInvitationView(
                old.id(), old.matchId(), old.senderEnterpriseId(), old.recipientEnterpriseId(),
                old.invitationType(), accepted ? "ACCEPTED" : "REJECTED", old.message(), clean(comment),
                old.sentBySubject(), actor.subject(), old.expiresAt(), now,
                old.version() + 1, old.createdAt(), now);
        invitations.put(invitationId, updated);
        return Optional.of(updated);
    }

    @Override
    public NegotiationView addNegotiation(
            UUID matchId,
            UUID associationId,
            UUID enterpriseId,
            NegotiationRequest request,
            ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireWrite(matchId, actor);
        requireSelectedEnterprise(enterpriseId, actor);
        NegotiationView value = new NegotiationView(
                UUID.randomUUID(), matchId, enterpriseId, request.stage().trim(),
                request.summary().trim(), clean(request.nextAction()), request.nextActionAt(),
                actor.subject(), Instant.now());
        negotiations.put(value.id(), value);
        latestNegotiationIds.put(matchId, value.id());
        return value;
    }

    @Override
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        return negotiations.values().stream()
                .filter(value -> value.matchId().equals(matchId))
                .filter(value -> canRead(value.matchId(), actor))
                .sorted(Comparator.comparing(NegotiationView::createdAt).reversed()
                        .thenComparing(NegotiationView::id))
                .toList();
    }

    @Override
    public Optional<NegotiationView> latestNegotiation(UUID matchId, ActorScope actor) {
        if (!canRead(matchId, actor)) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestNegotiationIds.get(matchId)).map(negotiations::get);
    }

    @Override
    public MatchFeedbackView upsertFeedback(
            UUID matchId, UUID enterpriseId, MatchFeedbackRequest request, ActorScope actor) {
        requireWrite(matchId, actor);
        requireSelectedEnterprise(enterpriseId, actor);
        UUID id = UUID.nameUUIDFromBytes(
                (matchId + ":" + enterpriseId).getBytes(StandardCharsets.UTF_8));
        MatchFeedbackView value = new MatchFeedbackView(
                id, matchId, enterpriseId, request.rating(), request.outcome().trim(),
                clean(request.closeReason()), clean(request.comment()), actor.subject(), Instant.now());
        feedback.put(id, value);
        return value;
    }

    @Override
    public List<MatchFeedbackView> feedback(UUID matchId, ActorScope actor) {
        return feedback.values().stream()
                .filter(value -> value.matchId().equals(matchId))
                .filter(value -> canRead(value.matchId(), actor))
                .sorted(Comparator.comparing(MatchFeedbackView::submittedAt).reversed()
                        .thenComparing(MatchFeedbackView::id))
                .toList();
    }

    @Override
    public OutcomeArchiveView archive(
            UUID matchId, UUID associationId, OutcomeArchiveRequest request, ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireWrite(matchId, actor);
        OutcomeArchiveView value = new OutcomeArchiveView(
                UUID.randomUUID(), matchId, request.title().trim(), request.summary().trim(),
                request.contractAmount(), request.resultType().trim(),
                request.visibility() == null ? "ASSOCIATION" : request.visibility(),
                actor.subject(), Instant.now(), 0);
        outcomes.put(value.id(), value);
        return value;
    }

    @Override
    public List<OutcomeArchiveView> outcomes(UUID matchId, ActorScope actor) {
        return outcomes.values().stream()
                .filter(value -> value.matchId().equals(matchId))
                .filter(value -> canRead(value.matchId(), actor))
                .sorted(Comparator.comparing(OutcomeArchiveView::archivedAt).reversed()
                        .thenComparing(OutcomeArchiveView::id))
                .toList();
    }

    private boolean canRead(UUID matchId, ActorScope actor) {
        MatchContext context = matchContexts.get(matchId);
        if (context == null) {
            return actor.isSystemAdmin() && actor.associationId() == null && actor.enterpriseId() == null;
        }
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null;
            }
            return actor.associationId().equals(context.associationId())
                    && (actor.enterpriseId() == null
                    || actor.enterpriseId().equals(context.demandEnterpriseId())
                    || actor.enterpriseId().equals(context.candidateEnterpriseId()));
        }
        if (actor.isAssociationStaff()) {
            return actor.associationId() != null
                    && actor.associationId().equals(context.associationId());
        }
        return enterpriseLifecycle.isOperational(context.demandEnterpriseId())
                && enterpriseLifecycle.isOperational(context.candidateEnterpriseId())
                && actor.enterpriseId() != null
                && (actor.enterpriseId().equals(context.demandEnterpriseId())
                || actor.enterpriseId().equals(context.candidateEnterpriseId()));
    }

    private boolean canWrite(UUID matchId, ActorScope actor) {
        MatchContext context = matchContexts.get(matchId);
        return context != null
                && enterpriseLifecycle.isOperational(context.demandEnterpriseId())
                && enterpriseLifecycle.isOperational(context.candidateEnterpriseId())
                && (!actor.isSystemAdmin() || actor.associationId() != null)
                && canRead(matchId, actor);
    }

    private void requireWrite(UUID matchId, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (!canWrite(matchId, actor)) {
            throw new ForbiddenException(
                    "MATCH_SCOPE_VIOLATION", "workflow record is outside the authenticated data scope");
        }
    }

    private static void requireAssociationArgument(UUID associationId, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (actor.associationId() == null
                || !Objects.equals(actor.associationId(), associationId)) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "workflow association is outside the authenticated data scope");
        }
    }

    private static void requireSender(UUID senderEnterpriseId, ActorScope actor) {
        if (!Objects.equals(senderEnterpriseId, actor.enterpriseId())) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "invitation sender is outside the selected enterprise context");
        }
    }

    private static void requireSelectedEnterprise(UUID enterpriseId, ActorScope actor) {
        if (actor.enterpriseId() == null
                || !actor.enterpriseId().equals(enterpriseId)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "workflow enterprise is outside the authenticated data scope");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MatchContext(
            UUID associationId, UUID demandEnterpriseId, UUID candidateEnterpriseId) {
    }
}
