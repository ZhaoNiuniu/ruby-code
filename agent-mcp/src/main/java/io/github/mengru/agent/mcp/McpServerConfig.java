package io.github.mengru.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpServerConfig(String name, String command, List<String> args, Map<String, String> env) {

    public McpServerConfig {
        name = validateName(name);
        if (command == null || command.isBlank()) {
            throw new McpException("MCP server '" + name + "' command must not be blank.");
        }
        command = command.strip();
        args = List.copyOf(args == null ? List.of() : args);
        for (String arg : args) {
            if (arg == null) {
                throw new McpException("MCP server '" + name + "' args must not contain null.");
            }
        }
        env = Map.copyOf(env == null ? Map.of() : env);
        for (Map.Entry<String, String> entry : env.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "env key must not be null");
            Objects.requireNonNull(entry.getValue(), "env value must not be null");
        }
    }

    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new McpException("MCP server name must not be blank.");
        }
        String stripped = name.strip();
        if (!stripped.matches("[A-Za-z0-9_-]+")) {
            throw new McpException("MCP server name must match [A-Za-z0-9_-]+: " + stripped);
        }
        return stripped;
    }
}
