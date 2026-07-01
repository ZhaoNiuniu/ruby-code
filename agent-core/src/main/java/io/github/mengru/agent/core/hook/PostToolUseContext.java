package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.util.Objects;

public record PostToolUseContext(
        AgentRequest request,
        AgentStep toolCall,
        Tool tool,
        ToolRequest toolRequest,
        ToolResult toolResult
) {

    public PostToolUseContext {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(toolRequest, "toolRequest must not be null");
        Objects.requireNonNull(toolResult, "toolResult must not be null");
    }
}
