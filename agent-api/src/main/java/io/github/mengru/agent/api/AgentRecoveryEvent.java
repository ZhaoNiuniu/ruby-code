package io.github.mengru.agent.api;

import java.util.Objects;

public record AgentRecoveryEvent(
        ModelErrorCode errorCode,
        String action,
        int attempt,
        boolean recovered,
        int maxOutputTokensBefore,
        int maxOutputTokensAfter,
        String message
) {

    public AgentRecoveryEvent {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(action, "action must not be null");
        message = message == null ? "" : message;
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
    }
}
