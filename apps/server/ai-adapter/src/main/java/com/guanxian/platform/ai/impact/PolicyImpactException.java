package com.guanxian.platform.ai.impact;

public final class PolicyImpactException extends RuntimeException {
    private final Reason reason;

    public PolicyImpactException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_FOUND,
        FORBIDDEN,
        CONFLICT,
        PRECONDITION_FAILED,
        EVIDENCE_REQUIRED
    }
}
