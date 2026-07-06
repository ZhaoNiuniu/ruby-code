package io.github.mengru.agent.core.team;

import java.util.Objects;

public record TeamPermissionReviewDecision(Outcome outcome, String reason) {

    public enum Outcome {
        DENY,
        ESCALATE_TO_USER
    }

    public TeamPermissionReviewDecision {
        Objects.requireNonNull(outcome, "outcome must not be null");
        reason = reason == null ? "" : reason.strip();
    }

    public static TeamPermissionReviewDecision deny(String reason) {
        return new TeamPermissionReviewDecision(Outcome.DENY, reason);
    }

    public static TeamPermissionReviewDecision escalateToUser(String reason) {
        return new TeamPermissionReviewDecision(Outcome.ESCALATE_TO_USER, reason);
    }
}
