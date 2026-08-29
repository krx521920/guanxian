package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryEcosystemWorkflowStore implements EcosystemWorkflowStore {
    private final ConcurrentMap<UUID, MatchInvitationView> invitations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, NegotiationView> negotiations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, MatchFeedbackView> feedback = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, OutcomeArchiveView> outcomes = new ConcurrentHashMap<>();

    @Override
    public MatchInvitationView createInvitation(
            UUID matchId,
            UUID associationId,
            UUID senderEnterpriseId,
            MatchInvitationRequest request,
            ActorScope actor) {
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
                .sorted(Comparator.comparing(MatchInvitationView::createdAt).reversed()
                        .thenComparing(MatchInvitationView::id))
                .toList();
    }

    @Override
    public Optional<MatchInvitationView> findInvitation(UUID invitationId, ActorScope actor) {
        return Optional.ofNullable(invitations.get(invitationId));
    }

    @Override
    public synchronized void expirePendingInvitations(UUID matchId) {
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
    public boolean hasPendingInvitation(UUID matchId) {
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
        if (old == null || old.version() != expectedVersion || !"PENDING".equals(old.status())) {
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
        NegotiationView value = new NegotiationView(
                UUID.randomUUID(), matchId, enterpriseId, request.stage().trim(),
                request.summary().trim(), clean(request.nextAction()), request.nextActionAt(),
                actor.subject(), Instant.now());
        negotiations.put(value.id(), value);
        return value;
    }

    @Override
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        return negotiations.values().stream()
                .filter(value -> value.matchId().equals(matchId))
                .sorted(Comparator.comparing(NegotiationView::createdAt).reversed()
                        .thenComparing(NegotiationView::id))
                .toList();
    }

    @Override
    public Optional<NegotiationView> latestNegotiation(UUID matchId, ActorScope actor) {
        return negotiations(matchId, actor).stream().findFirst();
    }

    @Override
    public MatchFeedbackView upsertFeedback(
            UUID matchId, UUID enterpriseId, MatchFeedbackRequest request, ActorScope actor) {
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
                .sorted(Comparator.comparing(MatchFeedbackView::submittedAt).reversed()
                        .thenComparing(MatchFeedbackView::id))
                .toList();
    }

    @Override
    public OutcomeArchiveView archive(
            UUID matchId, UUID associationId, OutcomeArchiveRequest request, ActorScope actor) {
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
                .sorted(Comparator.comparing(OutcomeArchiveView::archivedAt).reversed()
                        .thenComparing(OutcomeArchiveView::id))
                .toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
