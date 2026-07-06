package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        String startFile = System.getenv("MCP_FAKE_START_FILE");
        if (startFile != null && !startFile.isBlank()) {
            Path path = Path.of(startFile);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "started\n", StandardCharsets.UTF_8,
                    Files.exists(path)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode request = MAPPER.readTree(line);
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                continue;
            }
            JsonNode id = request.get("id");
            if ("initialize".equals(method)) {
                send(id, initializeResult());
            } else if ("tools/list".equals(method)) {
                send(id, toolsListResult());
            } else if ("tools/call".equals(method)) {
                send(id, callResult(request.path("params").path("arguments").path("text").asText("")));
            } else {
                sendError(id, -32601, "unknown method: " + method);
            }
        }
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        result.putObject("serverInfo").put("name", "fake-cli-mcp").put("version", "1.0.0");
        return result;
    }

    private static ObjectNode toolsListResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "echo");
        tool.put("description", "Echo text from the fake MCP server.");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        return result;
    }

    private static ObjectNode callResult(String text) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", "mcp echo: " + text);
        result.putObject("structuredContent").put("text", text);
        result.put("isError", false);
        return result;
    }

    private static void send(JsonNode id, ObjectNode result) throws Exception {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        System.out.println(MAPPER.writeValueAsString(response));
        System.out.flush();
    }

    private static void sendError(JsonNode id, int code, String message) throws Exception {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        System.out.println(MAPPER.writeValueAsString(response));
        System.out.flush();
    }
}
