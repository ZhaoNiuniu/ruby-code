package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpConfig {

    public static final String DEFAULT_FILE_NAME = ".mcp.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final List<McpServerConfig> servers;

    private McpConfig(Path path, List<McpServerConfig> servers) {
        this.path = path == null ? null : path.toAbsolutePath().normalize();
        this.servers = List.copyOf(servers);
    }

    public static McpConfig empty(Path path) {
        return new McpConfig(path, List.of());
    }

    public static McpConfig loadDefault(Path workspace) {
        return load(workspace, workspace.resolve(DEFAULT_FILE_NAME));
    }

    public static McpConfig load(Path workspace, Path configPath) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(configPath, "configPath must not be null");
        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Path resolvedConfig = configPath.isAbsolute()
                ? configPath.toAbsolutePath().normalize()
                : workspaceRoot.resolve(configPath).normalize();
        if (!resolvedConfig.startsWith(workspaceRoot)) {
            throw new McpException("MCP config path must stay within the workspace: " + configPath);
        }
        if (!Files.exists(resolvedConfig)) {
            return empty(resolvedConfig);
        }
        try {
            JsonNode root = MAPPER.readTree(resolvedConfig.toFile());
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || serversNode.isNull()) {
                throw new McpException("MCP config must contain an object field 'mcpServers'.");
            }
            if (!serversNode.isObject()) {
                throw new McpException("MCP config field 'mcpServers' must be an object.");
            }
            List<McpServerConfig> servers = new ArrayList<>();
            serversNode.fields().forEachRemaining(entry -> servers.add(parseServer(entry.getKey(), entry.getValue())));
            return new McpConfig(resolvedConfig, servers);
        } catch (IOException e) {
            throw new McpException("Failed to read MCP config " + resolvedConfig + ": " + e.getMessage(), e);
        }
    }

    public Path path() {
        return path;
    }

    public List<McpServerConfig> servers() {
        return servers;
    }

    public boolean isEmpty() {
        return servers.isEmpty();
    }

    private static McpServerConfig parseServer(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new McpException("MCP server '" + name + "' config must be an object.");
        }
        String command = text(node.get("command"));
        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.get("args");
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                throw new McpException("MCP server '" + name + "' args must be an array.");
            }
            for (JsonNode arg : argsNode) {
                if (!arg.isTextual()) {
                    throw new McpException("MCP server '" + name + "' args must contain only strings.");
                }
                args.add(arg.asText());
            }
        }
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.get("env");
        if (envNode != null && !envNode.isNull()) {
            if (!envNode.isObject()) {
                throw new McpException("MCP server '" + name + "' env must be an object.");
            }
            envNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw new McpException("MCP server '" + name + "' env values must be strings.");
                }
                env.put(entry.getKey(), entry.getValue().asText());
            });
        }
        return new McpServerConfig(name, command, args, env);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }
}
