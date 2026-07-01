package io.github.mengru.agent.core;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;

import java.util.List;
import java.util.Objects;

public final class EchoModelClient implements ModelClient {

    @Override
    public AgentStep nextStep(AgentRequest request, List<AgentStep> previousSteps, List<Tool> tools) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(previousSteps, "previousSteps must not be null");
        Objects.requireNonNull(tools, "tools must not be null");

        if (previousSteps.isEmpty()) {
            return tools.stream()
                    .filter(tool -> "echo".equals(tool.name()))
                    .findFirst()
                    .map(tool -> AgentStep.toolCall(tool.name(), request.task()))
                    .orElseGet(() -> AgentStep.finalAnswer("Echo agent completed: " + request.task()));
        }

        return previousSteps.stream()
                .filter(step -> step.type() == AgentStep.Type.TOOL_RESULT)
                .reduce((first, second) -> second)
                .map(step -> AgentStep.finalAnswer("Echo agent completed: " + step.content()))
                .orElseGet(() -> AgentStep.finalAnswer("Echo agent completed: " + request.task()));
    }
}
