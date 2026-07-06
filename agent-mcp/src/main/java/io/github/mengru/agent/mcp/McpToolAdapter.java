package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.util.Objects;

public final class McpToolAdapter implements Tool {

    private final String serverName;
    private final McpStdioClient client;
    private final McpToolDefinition definition;
    private final String exposedName;

    public McpToolAdapter(String serverName, McpStdioClient client, McpToolDefinition definition) {
        this.serverName = McpServerConfig.validateName(serverName);
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.definition = Objects.requireNonNull(definition, "definition must not be null");
        this.exposedName = McpToolName.exposedName(this.serverName, definition.name());
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        String description = definition.description().isBlank()
                ? "MCP tool " + definition.name() + " from server " + serverName
                : definition.description();
        return "[MCP " + serverName + "] " + description;
    }

    @Override
    public JsonNode parametersSchema() {
        return definition.inputSchema();
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            McpCallResult result = client.callTool(definition.name(), request.arguments());
            return result.error() ? ToolResult.failure(result.output()) : ToolResult.success(result.output());
        } catch (RuntimeException e) {
            return ToolResult.failure("MCP tool " + exposedName + " failed: " + e.getMessage());
        }
    }
}
