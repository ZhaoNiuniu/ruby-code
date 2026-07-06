package io.github.mengru.agent.mcp;

final class McpToolName {

    private McpToolName() {
    }

    static String exposedName(String serverName, String toolName) {
        McpServerConfig.validateName(serverName);
        if (toolName == null || toolName.isBlank()) {
            throw new McpException("MCP tool name must not be blank.");
        }
        String stripped = toolName.strip();
        if (!stripped.matches("[A-Za-z0-9_-]+")) {
            throw new McpException("MCP tool name must match [A-Za-z0-9_-]+ for server '" + serverName + "': " + stripped);
        }
        return "mcp__" + serverName + "__" + stripped;
    }

    static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }
}
