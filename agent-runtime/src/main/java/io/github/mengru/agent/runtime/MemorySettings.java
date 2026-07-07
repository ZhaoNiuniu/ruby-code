package io.github.mengru.agent.runtime;

public record MemorySettings(Boolean persistent, Boolean session) {

    public static MemorySettings empty() {
        return new MemorySettings(null, null);
    }

    public MemorySettings merge(MemorySettings override) {
        if (override == null) {
            return this;
        }
        return new MemorySettings(
                override.persistent == null ? persistent : override.persistent,
                override.session == null ? session : override.session
        );
    }
}
