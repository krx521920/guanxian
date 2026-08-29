package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.PreconditionFailedException;

import java.util.List;
import java.util.Set;

final class MatchLifecycle {
    static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    static final String RECOMMENDED = "RECOMMENDED";
    static final String PARTIALLY_CONFIRMED = "PARTIALLY_CONFIRMED";
    static final String CONFIRMED = "CONFIRMED";
    static final String INVITED = "INVITED";
    static final String NEGOTIATING = "NEGOTIATING";
    static final String OUTCOME_PENDING = "OUTCOME_PENDING";
    static final String ARCHIVED = "ARCHIVED";
    static final String CLOSED = "CLOSED";

    static final String INITIAL_CONTACT = "INITIAL_CONTACT";
    static final String TECHNICAL_EXCHANGE = "TECHNICAL_EXCHANGE";
    static final String COMMERCIAL_NEGOTIATION = "COMMERCIAL_NEGOTIATION";
    static final String CONTRACTING = "CONTRACTING";
    static final String CONTRACT_SIGNED = "CONTRACT_SIGNED";
    static final String TERMINATED = "TERMINATED";

    private static final Set<String> CONFIRMABLE = Set.of(
            PENDING_CONFIRMATION, RECOMMENDED, PARTIALLY_CONFIRMED);
    private static final Set<String> CLOSABLE = Set.of(
            PENDING_CONFIRMATION, RECOMMENDED, PARTIALLY_CONFIRMED,
            CONFIRMED, INVITED, NEGOTIATING, OUTCOME_PENDING);
    private static final List<String> NEGOTIATION_STAGES = List.of(
            INITIAL_CONTACT,
            TECHNICAL_EXCHANGE,
            COMMERCIAL_NEGOTIATION,
            CONTRACTING,
            CONTRACT_SIGNED);

    private MatchLifecycle() {
    }

    static void requireConfirmable(PersistedMatchView match) {
        requireState(match.state(), CONFIRMABLE, "confirm");
    }

    static void requireRecommendationAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(PENDING_CONFIRMATION, PARTIALLY_CONFIRMED), "recommend");
        if (match.recommendedAt() != null) {
            throw new PreconditionFailedException("match has already been recommended");
        }
    }

    static void requireInvitationAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(CONFIRMED), "invite");
        if (match.demandConfirmedAt() == null || match.candidateConfirmedAt() == null) {
            throw new PreconditionFailedException("both enterprises must confirm before an invitation is sent");
        }
    }

    static void requireInvitationResponseAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(INVITED), "respond to invitation");
    }

    static void requireNegotiationAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(NEGOTIATING), "record negotiation");
    }

    static void requireFeedbackAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(OUTCOME_PENDING, CLOSED), "submit feedback");
    }

    static void requireOutcomeAllowed(PersistedMatchView match) {
        requireState(match.state(), Set.of(OUTCOME_PENDING), "archive outcome");
    }

    static void requireClosable(PersistedMatchView match) {
        requireState(match.state(), CLOSABLE, "close");
    }

    static void requireNextNegotiationStage(String previousStage, String requestedStage) {
        if (TERMINATED.equals(requestedStage)) {
            return;
        }
        int requested = NEGOTIATION_STAGES.indexOf(requestedStage);
        if (requested < 0) {
            throw new PreconditionFailedException("unsupported negotiation stage " + requestedStage);
        }
        if (previousStage == null) {
            if (requested != 0) {
                throw new PreconditionFailedException("negotiation must start at INITIAL_CONTACT");
            }
            return;
        }
        if (TERMINATED.equals(previousStage) || CONTRACT_SIGNED.equals(previousStage)) {
            throw new PreconditionFailedException("negotiation has already reached a terminal stage");
        }
        int previous = NEGOTIATION_STAGES.indexOf(previousStage);
        if (requested < previous || requested > previous + 1) {
            throw new PreconditionFailedException(
                    "negotiation stage must remain at the current stage or advance by one stage");
        }
    }

    private static void requireState(String actual, Set<String> allowed, String operation) {
        if (!allowed.contains(actual)) {
            throw new PreconditionFailedException(
                    "match state " + actual + " does not allow operation: " + operation);
        }
    }
}
