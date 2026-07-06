package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public record McpToolDefinition(String name, String title, String description, JsonNode inputSchema) {

    public McpToolDefinition {
        if (name == null || name.isBlank()) {
            throw new McpException("MCP tool name must not be blank.");
        }
        name = name.strip();
        title = title == null ? "" : title.strip();
        description = description == null ? "" : description.strip();
        inputSchema = inputSchema == null || inputSchema.isNull()
                ? JsonNodeFactory.instance.objectNode().put("type", "object")
                : inputSchema.deepCopy();
    }
}
