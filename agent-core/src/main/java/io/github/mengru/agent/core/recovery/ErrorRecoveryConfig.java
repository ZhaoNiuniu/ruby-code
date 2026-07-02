package io.github.mengru.agent.core.recovery;

import java.time.Duration;
import java.util.Objects;

public record ErrorRecoveryConfig(
        boolean enabled,
        int transientRetryAttempts,
        int recoveryMaxOutputTokens,
        Duration baseDelay,
        boolean jitterEnabled
) {

    public static final int DEFAULT_TRANSIENT_RETRY_ATTEMPTS = 3;
    public static final int DEFAULT_RECOVERY_MAX_OUTPUT_TOKENS = 65536;
    public static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(500);

    public ErrorRecoveryConfig {
        Objects.requireNonNull(baseDelay, "baseDelay must not be null");
        if (transientRetryAttempts < 0) {
            throw new IllegalArgumentException("transientRetryAttempts must not be negative");
        }
        if (recoveryMaxOutputTokens < 1) {
            throw new IllegalArgumentException("recoveryMaxOutputTokens must be greater than zero");
        }
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative");
        }
    }

    public static ErrorRecoveryConfig defaults() {
        return new ErrorRecoveryConfig(
                true,
                DEFAULT_TRANSIENT_RETRY_ATTEMPTS,
                DEFAULT_RECOVERY_MAX_OUTPUT_TOKENS,
                DEFAULT_BASE_DELAY,
                true
        );
    }

    public static ErrorRecoveryConfig disabled() {
        return new ErrorRecoveryConfig(
                false,
                0,
                DEFAULT_RECOVERY_MAX_OUTPUT_TOKENS,
                DEFAULT_BASE_DELAY,
                false
        );
    }
}
