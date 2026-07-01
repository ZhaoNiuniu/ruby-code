package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;

import java.util.Objects;

public record UserPromptSubmitContext(AgentRequest request) {

    public UserPromptSubmitContext {
        Objects.requireNonNull(request, "request must not be null");
    }
}
