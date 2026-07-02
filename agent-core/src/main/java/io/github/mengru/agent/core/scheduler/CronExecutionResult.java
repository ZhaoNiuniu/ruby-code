package io.github.mengru.agent.core.scheduler;

import io.github.mengru.agent.api.AgentResult;

public record CronExecutionResult(
        CronTriggeredTask task,
        AgentResult result,
        String error
) {

    public boolean success() {
        return error == null || error.isBlank();
    }
}
