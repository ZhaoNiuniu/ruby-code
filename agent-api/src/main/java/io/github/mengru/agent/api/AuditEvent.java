package io.github.mengru.agent.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AuditEvent(
        String runId,
        long seq,
        String timestamp,
        Type type,
        String actor,
        String phase,
        String parentRunId,
        String correlationId,
        Map<String, String> attributes
) {

    public enum Type {
        RUN_START,
        MODEL_CALL,
        TOOL_CALL,
        TOOL_RESULT,
        TASK_NOTIFICATION,
        COMPRESSION,
        RECOVERY,
        PERMISSION_DENIED,
        ERROR,
        FINAL_ANSWER,
        RUN_END
    }

    public AuditEvent {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(type, "type must not be null");
        actor = actor == null ? "" : actor;
        phase = phase == null ? "" : phase;
        parentRunId = parentRunId == null ? "" : parentRunId;
        correlationId = correlationId == null ? "" : correlationId;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static AuditEvent of(
            String runId,
            long seq,
            Type type,
            String actor,
            String phase,
            String parentRunId,
            String correlationId,
            Map<String, String> attributes
    ) {
        return new AuditEvent(runId, seq, java.time.Instant.now().toString(), type, actor, phase, parentRunId, correlationId, attributes);
    }
}
