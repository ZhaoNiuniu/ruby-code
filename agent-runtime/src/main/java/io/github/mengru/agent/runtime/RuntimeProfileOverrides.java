package io.github.mengru.agent.runtime;

import java.nio.file.Path;

public record RuntimeProfileOverrides(
        String provider,
        String model,
        String baseUrl,
        String system,
        Boolean mcpEnabled,
        Path mcpConfig,
        Boolean contextCompressionEnabled,
        Integer contextWindowTokens,
        Integer maxOutputTokens,
        Integer reservedTokens,
        Boolean errorRecoveryEnabled,
        Integer modelRetryAttempts,
        Integer generationMaxOutputTokens,
        Integer recoveryMaxOutputTokens,
        Boolean traceEnabled,
        String traceSink
) {

    public static Builder builder() {
        return new Builder();
    }

    public static RuntimeProfileOverrides none() {
        return builder().build();
    }

    AgentRuntimeProfile toProfile() {
        return new AgentRuntimeProfile(
                blankToNull(provider),
                blankToNull(model),
                blankToNull(baseUrl),
                system,
                new ToolSettings(null, null, null, null, mcpEnabled, mcpConfig == null ? null : mcpConfig.toString()),
                null,
                new ContextCompressionSettings(contextCompressionEnabled, contextWindowTokens, maxOutputTokens, reservedTokens),
                new ErrorRecoverySettings(errorRecoveryEnabled, modelRetryAttempts, generationMaxOutputTokens, recoveryMaxOutputTokens),
                new TraceSettings(traceEnabled, blankToNull(traceSink)),
                null
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static final class Builder {

        private String provider;
        private String model;
        private String baseUrl;
        private String system;
        private Boolean mcpEnabled;
        private Path mcpConfig;
        private Boolean contextCompressionEnabled;
        private Integer contextWindowTokens;
        private Integer maxOutputTokens;
        private Integer reservedTokens;
        private Boolean errorRecoveryEnabled;
        private Integer modelRetryAttempts;
        private Integer generationMaxOutputTokens;
        private Integer recoveryMaxOutputTokens;
        private Boolean traceEnabled;
        private String traceSink;

        private Builder() {
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder mcpEnabled(Boolean mcpEnabled) {
            this.mcpEnabled = mcpEnabled;
            return this;
        }

        public Builder mcpConfig(Path mcpConfig) {
            this.mcpConfig = mcpConfig;
            return this;
        }

        public Builder contextCompressionEnabled(Boolean contextCompressionEnabled) {
            this.contextCompressionEnabled = contextCompressionEnabled;
            return this;
        }

        public Builder contextWindowTokens(Integer contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder reservedTokens(Integer reservedTokens) {
            this.reservedTokens = reservedTokens;
            return this;
        }

        public Builder errorRecoveryEnabled(Boolean errorRecoveryEnabled) {
            this.errorRecoveryEnabled = errorRecoveryEnabled;
            return this;
        }

        public Builder modelRetryAttempts(Integer modelRetryAttempts) {
            this.modelRetryAttempts = modelRetryAttempts;
            return this;
        }

        public Builder generationMaxOutputTokens(Integer generationMaxOutputTokens) {
            this.generationMaxOutputTokens = generationMaxOutputTokens;
            return this;
        }

        public Builder recoveryMaxOutputTokens(Integer recoveryMaxOutputTokens) {
            this.recoveryMaxOutputTokens = recoveryMaxOutputTokens;
            return this;
        }

        public Builder traceEnabled(Boolean traceEnabled) {
            this.traceEnabled = traceEnabled;
            return this;
        }

        public Builder traceSink(String traceSink) {
            this.traceSink = traceSink;
            return this;
        }

        public RuntimeProfileOverrides build() {
            return new RuntimeProfileOverrides(
                    provider,
                    model,
                    baseUrl,
                    system,
                    mcpEnabled,
                    mcpConfig,
                    contextCompressionEnabled,
                    contextWindowTokens,
                    maxOutputTokens,
                    reservedTokens,
                    errorRecoveryEnabled,
                    modelRetryAttempts,
                    generationMaxOutputTokens,
                    recoveryMaxOutputTokens,
                    traceEnabled,
                    traceSink
            );
        }
    }
}
