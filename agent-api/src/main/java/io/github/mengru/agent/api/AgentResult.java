package io.github.mengru.agent.api;

import java.util.List;
import java.util.Objects;

public record AgentResult(
        String output,
        List<AgentStep> steps,
        boolean completed,
        List<ContextCompressionEvent> compressionEvents,
        List<AgentRecoveryEvent> recoveryEvents,
        List<TraceEvent> traceEvents,
        String runId,
        List<AuditEvent> auditEvents
) {

    public AgentResult(String output, List<AgentStep> steps, boolean completed) {
        this(output, steps, completed, List.of());
    }

    public AgentResult(
            String output,
            List<AgentStep> steps,
            boolean completed,
            List<ContextCompressionEvent> compressionEvents
    ) {
        this(output, steps, completed, compressionEvents, List.of());
    }

    public AgentResult(
            String output,
            List<AgentStep> steps,
            boolean completed,
            List<ContextCompressionEvent> compressionEvents,
            List<AgentRecoveryEvent> recoveryEvents
    ) {
        this(output, steps, completed, compressionEvents, recoveryEvents, List.of());
    }

    public AgentResult(
            String output,
            List<AgentStep> steps,
            boolean completed,
            List<ContextCompressionEvent> compressionEvents,
            List<AgentRecoveryEvent> recoveryEvents,
            List<TraceEvent> traceEvents
    ) {
        this(output, steps, completed, compressionEvents, recoveryEvents, traceEvents, "", List.of());
    }

    public AgentResult {
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(steps, "steps must not be null");
        Objects.requireNonNull(compressionEvents, "compressionEvents must not be null");
        Objects.requireNonNull(recoveryEvents, "recoveryEvents must not be null");
        Objects.requireNonNull(traceEvents, "traceEvents must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        steps = List.copyOf(steps);
        compressionEvents = List.copyOf(compressionEvents);
        recoveryEvents = List.copyOf(recoveryEvents);
        traceEvents = List.copyOf(traceEvents);
        auditEvents = List.copyOf(auditEvents);
    }
}
