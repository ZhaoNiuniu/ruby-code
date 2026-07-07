package io.github.mengru.agent.runtime;

public record ContextCompressionSettings(
        Boolean enabled,
        Integer contextWindowTokens,
        Integer maxOutputTokens,
        Integer reservedTokens
) {

    public static ContextCompressionSettings empty() {
        return new ContextCompressionSettings(null, null, null, null);
    }

    public ContextCompressionSettings merge(ContextCompressionSettings override) {
        if (override == null) {
            return this;
        }
        return new ContextCompressionSettings(
                override.enabled == null ? enabled : override.enabled,
                override.contextWindowTokens == null ? contextWindowTokens : override.contextWindowTokens,
                override.maxOutputTokens == null ? maxOutputTokens : override.maxOutputTokens,
                override.reservedTokens == null ? reservedTokens : override.reservedTokens
        );
    }
}
