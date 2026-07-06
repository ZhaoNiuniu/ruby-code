package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "normal" : args[0];
        if ("malformed".equals(mode)) {
            System.out.println("{bad");
            System.out.flush();
            Thread.sleep(10_000);
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode request = MAPPER.readTree(line);
                if (!request.has("id")) {
                    continue;
                }
                String method = request.path("method").asText();
                JsonNode id = request.get("id");
                if ("initialize".equals(method)) {
                    respond(id, object().put("protocolVersion", McpStdioClient.PROTOCOL_VERSION).set("capabilities", object()));
                } else if ("tools/list".equals(method)) {
                    respond(id, toolsList(request));
                } else if ("tools/call".equals(method)) {
                    handleCall(id, request, mode);
                } else {
                    error(id, -32601, "unknown method");
                }
            }
        }
    }

    private static ObjectNode toolsList(JsonNode request) {
        String cursor = request.path("params").path("cursor").asText("");
        ObjectNode result = object();
        if (cursor.isBlank()) {
            result.putArray("tools")
                    .add(tool("echo", "Echo text"))
                    .add(tool("fail", "Fail intentionally"));
            result.put("nextCursor", "page2");
        } else {
            result.putArray("tools")
                    .add(tool("second", "Second page tool"));
        }
        return result;
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode tool = object();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = object();
        schema.put("type", "object");
        ObjectNode properties = object();
        properties.set("text", object().put("type", "string"));
        schema.set("properties", properties);
        tool.set("inputSchema", schema);
        return tool;
    }

    private static void handleCall(JsonNode id, JsonNode request, String mode) throws Exception {
        String name = request.path("params").path("name").asText();
        if ("jsonrpc_error".equals(mode)) {
            error(id, -32000, "server exploded");
            return;
        }
        ObjectNode result = object();
        result.put("isError", "fail".equals(name));
        result.putArray("content").add(object()
                .put("type", "text")
                .put("text", request.path("params").path("arguments").path("text").asText("")));
        result.set("structuredContent", object().put("tool", name));
        respond(id, result);
    }

    private static void respond(JsonNode id, JsonNode result) throws Exception {
        ObjectNode response = object();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        System.out.println(MAPPER.writeValueAsString(response));
        System.out.flush();
    }

    private static void error(JsonNode id, int code, String message) throws Exception {
        ObjectNode response = object();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", object().put("code", code).put("message", message));
        System.out.println(MAPPER.writeValueAsString(response));
        System.out.flush();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
