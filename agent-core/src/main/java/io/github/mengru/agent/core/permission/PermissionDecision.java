package io.github.mengru.agent.core.permission;

import java.util.Objects;

public record PermissionDecision(Outcome outcome, String reason, String riskSummary) {

    public enum Outcome {
        ALLOW,
        DENY,
        ASK_USER
    }

    public PermissionDecision {
        Objects.requireNonNull(outcome, "outcome must not be null");
        reason = reason == null ? "" : reason;
        riskSummary = riskSummary == null ? "" : riskSummary;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Outcome.ALLOW, "", "");
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Outcome.DENY, reason, "");
    }

    public static PermissionDecision askUser(String reason, String riskSummary) {
        return new PermissionDecision(Outcome.ASK_USER, reason, riskSummary);
    }
}
