package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class McpStdioClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private final Duration timeout;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<String> stderrLines = java.util.Collections.synchronizedList(new ArrayList<>());
    private Process process;
    private BufferedWriter writer;
    private Thread stdoutReader;
    private Thread stderrReader;
    private volatile boolean closed;

    public McpStdioClient(McpServerConfig config) {
        this(config, DEFAULT_TIMEOUT);
    }

    public McpStdioClient(McpServerConfig config, Duration timeout) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    public String serverName() {
        return config.name();
    }

    public synchronized void start() {
        if (process != null) {
            return;
        }
        try {
            ArrayList<String> command = new ArrayList<>();
            command.add(config.command());
            command.addAll(config.args());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(config.env());
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutReader = new Thread(this::readStdout, "mcp-stdout-" + config.name());
            stdoutReader.setDaemon(true);
            stdoutReader.start();
            stderrReader = new Thread(this::readStderr, "mcp-stderr-" + config.name());
            stderrReader.setDaemon(true);
            stderrReader.start();
            initialize();
        } catch (IOException e) {
            throw new McpException("Failed to start MCP server '" + config.name() + "': " + e.getMessage(), e);
        }
    }

    public List<McpToolDefinition> listTools() {
        ensureStarted();
        List<McpToolDefinition> tools = new ArrayList<>();
        String cursor = "";
        do {
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            if (!cursor.isBlank()) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            JsonNode toolsNode = result.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                throw new McpException("MCP server '" + config.name() + "' tools/list response missing tools array.");
            }
            for (JsonNode tool : toolsNode) {
                tools.add(parseTool(tool));
            }
            JsonNode nextCursor = result.get("nextCursor");
            cursor = nextCursor == null || nextCursor.isNull() ? "" : nextCursor.asText("");
        } while (!cursor.isBlank());
        return List.copyOf(tools);
    }

    public McpCallResult callTool(String toolName, JsonNode arguments) {
        ensureStarted();
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null || arguments.isNull()
                ? JsonNodeFactory.instance.objectNode()
                : arguments.deepCopy());
        JsonNode result = request("tools/call", params);
        return McpCallResult.from(result);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new McpException("MCP server '" + config.name() + "' closed."));
        }
        pending.clear();
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void initialize() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", JsonNodeFactory.instance.objectNode());
        ObjectNode clientInfo = JsonNodeFactory.instance.objectNode();
        clientInfo.put("name", "java-agent");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        request("initialize", params);
        notify("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    private McpToolDefinition parseTool(JsonNode tool) {
        if (tool == null || !tool.isObject()) {
            throw new McpException("MCP server '" + config.name() + "' returned a non-object tool.");
        }
        String name = text(tool.get("name"));
        McpToolName.exposedName(config.name(), name);
        return new McpToolDefinition(
                name,
                text(tool.get("title")),
                text(tool.get("description")),
                tool.get("inputSchema")
        );
    }

    private JsonNode request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        write(message);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("MCP server '" + config.name() + "' timed out during " + method + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new McpException("Interrupted while waiting for MCP server '" + config.name() + "' " + method + ".");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException("MCP server '" + config.name() + "' failed during " + method + ": " + cause.getMessage(), cause);
        }
    }

    private void notify(String method, JsonNode params) {
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        write(message);
    }

    private synchronized void write(JsonNode message) {
        if (closed) {
            throw new McpException("MCP server '" + config.name() + "' is closed.");
        }
        try {
            writer.write(MAPPER.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new McpException("Failed to write to MCP server '" + config.name() + "': " + e.getMessage(), e);
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleMessage(line);
            }
            failPending("MCP server '" + config.name() + "' stdout closed.");
        } catch (IOException e) {
            failPending("Failed to read MCP server '" + config.name() + "' stdout: " + e.getMessage());
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stderrLines.size() < 200) {
                    stderrLines.add(line);
                }
            }
        } catch (IOException ignored) {
            // stderr is diagnostic only.
        }
    }

    private void handleMessage(String line) {
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (IOException e) {
            failPending("MCP server '" + config.name() + "' returned malformed JSON: " + line);
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || !idNode.canConvertToLong()) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new McpException("MCP server '" + config.name() + "' JSON-RPC error: " + error));
            return;
        }
        JsonNode result = message.get("result");
        future.complete(result == null || result.isNull() ? JsonNodeFactory.instance.objectNode() : result);
    }

    private void failPending(String message) {
        McpException exception = new McpException(message + stderrSuffix());
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(exception);
        }
        pending.clear();
    }

    private String stderrSuffix() {
        synchronized (stderrLines) {
            if (stderrLines.isEmpty()) {
                return "";
            }
            int from = Math.max(0, stderrLines.size() - 5);
            return " Recent stderr: " + String.join(" | ", stderrLines.subList(from, stderrLines.size()));
        }
    }

    private void ensureStarted() {
        if (process == null) {
            start();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }
}
