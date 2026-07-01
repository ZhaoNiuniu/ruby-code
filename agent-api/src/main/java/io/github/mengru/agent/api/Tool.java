package io.github.mengru.agent.api;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    ToolResult execute(ToolRequest request);
}
