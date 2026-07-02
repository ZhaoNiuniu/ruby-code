package io.github.mengru.agent.core.team;

import io.github.mengru.agent.api.AgentResult;

import java.util.List;

public record TeamExecutionResult(List<TeamMessage> messages, AgentResult result, boolean success, String error) {

    public TeamExecutionResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
        error = error == null ? "" : error;
    }
}
