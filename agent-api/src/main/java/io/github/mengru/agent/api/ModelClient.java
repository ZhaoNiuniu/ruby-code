package io.github.mengru.agent.api;

import java.util.List;

public interface ModelClient {

    AgentStep nextStep(AgentRequest request, List<AgentStep> previousSteps, List<Tool> tools);
}
