package io.github.mengru.agent.runtime;

public record TraceSettings(Boolean enabled, String sink) {

    public static TraceSettings empty() {
        return new TraceSettings(null, null);
    }

    public TraceSettings merge(TraceSettings override) {
        if (override == null) {
            return this;
        }
        return new TraceSettings(
                override.enabled == null ? enabled : override.enabled,
                override.sink == null ? sink : override.sink
        );
    }
}
