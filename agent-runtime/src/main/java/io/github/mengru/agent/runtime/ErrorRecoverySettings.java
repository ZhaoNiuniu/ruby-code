package io.github.mengru.agent.runtime;

public record ErrorRecoverySettings(
        Boolean enabled,
        Integer modelRetryAttempts,
        Integer generationMaxOutputTokens,
        Integer recoveryMaxOutputTokens
) {

    public static ErrorRecoverySettings empty() {
        return new ErrorRecoverySettings(null, null, null, null);
    }

    public ErrorRecoverySettings merge(ErrorRecoverySettings override) {
        if (override == null) {
            return this;
        }
        return new ErrorRecoverySettings(
                override.enabled == null ? enabled : override.enabled,
                override.modelRetryAttempts == null ? modelRetryAttempts : override.modelRetryAttempts,
                override.generationMaxOutputTokens == null ? generationMaxOutputTokens : override.generationMaxOutputTokens,
                override.recoveryMaxOutputTokens == null ? recoveryMaxOutputTokens : override.recoveryMaxOutputTokens
        );
    }
}
