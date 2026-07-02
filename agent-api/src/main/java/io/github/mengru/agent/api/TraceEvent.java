package io.github.mengru.agent.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TraceEvent(Type type, Map<String, String> attributes) {

    public enum Type {
        MODEL_CALL,
        TOOL_CALL,
        TOOL_RESULT,
        TASK_NOTIFICATION,
        COMPRESSION,
        RECOVERY,
        PERMISSION_DENIED,
        ERROR,
        FINAL_ANSWER
    }

    public TraceEvent {
        Objects.requireNonNull(type, "type must not be null");
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static TraceEvent of(Type type, Map<String, String> attributes) {
        return new TraceEvent(type, attributes);
    }
}
