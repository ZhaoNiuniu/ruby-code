package io.github.mengru.agent.mcp;

import io.github.mengru.agent.api.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class McpToolProvider implements AutoCloseable {

    private final List<McpStdioClient> clients;
    private final List<Tool> tools;

    private McpToolProvider(List<McpStdioClient> clients, List<Tool> tools) {
        this.clients = List.copyOf(clients);
        this.tools = List.copyOf(tools);
    }

    public static McpToolProvider empty() {
        return new McpToolProvider(List.of(), List.of());
    }

    public static McpToolProvider start(McpConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (config.isEmpty()) {
            return empty();
        }
        ArrayList<McpStdioClient> clients = new ArrayList<>();
        ArrayList<Tool> tools = new ArrayList<>();
        try {
            for (McpServerConfig server : config.servers()) {
                McpStdioClient client = new McpStdioClient(server);
                client.start();
                clients.add(client);
                for (McpToolDefinition definition : client.listTools()) {
                    tools.add(new McpToolAdapter(server.name(), client, definition));
                }
            }
            return new McpToolProvider(clients, tools);
        } catch (RuntimeException e) {
            closeAll(clients);
            throw e;
        }
    }

    public List<Tool> tools() {
        return tools;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    @Override
    public void close() {
        closeAll(clients);
    }

    private static void closeAll(List<McpStdioClient> clients) {
        for (McpStdioClient client : clients) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // Best effort close during shutdown.
            }
        }
    }
}
