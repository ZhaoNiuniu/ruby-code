package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;

import java.util.List;
import java.util.Objects;

public record StopContext(AgentRequest request, List<AgentStep> steps, AgentResult result, String reason) {

    public StopContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(steps, "steps must not be null");
        steps = List.copyOf(steps);
        Objects.requireNonNull(result, "result must not be null");
        reason = reason == null ? "" : reason;
    }
}
