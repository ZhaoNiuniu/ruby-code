package io.github.mengru.agent.api;

import java.util.List;
import java.util.Objects;

public record AgentResult(
        String output,
        List<AgentStep> steps,
        boolean completed,
        List<ContextCompressionEvent> compressionEvents
) {

    public AgentResult(String output, List<AgentStep> steps, boolean completed) {
        this(output, steps, completed, List.of());
    }

    public AgentResult {
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(steps, "steps must not be null");
        Objects.requireNonNull(compressionEvents, "compressionEvents must not be null");
        steps = List.copyOf(steps);
        compressionEvents = List.copyOf(compressionEvents);
    }
}
