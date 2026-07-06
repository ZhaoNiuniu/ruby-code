package io.github.mengru.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpCallResult(boolean error, String output) {

    static McpCallResult from(JsonNode result) {
        boolean isError = result != null && result.path("isError").asBoolean(false);
        StringBuilder builder = new StringBuilder();
        JsonNode content = result == null ? null : result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode item : content) {
                appendContent(builder, item);
            }
        }
        JsonNode structured = result == null ? null : result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("structuredContent:\n").append(structured.toPrettyString());
        }
        String output = builder.isEmpty() && result != null ? result.toPrettyString() : builder.toString();
        return new McpCallResult(isError, McpOutputSupport.truncate(output));
    }

    private static void appendContent(StringBuilder builder, JsonNode item) {
        if (item == null || !item.isObject()) {
            return;
        }
        String type = item.path("type").asText("");
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        if ("text".equals(type)) {
            builder.append(item.path("text").asText(""));
        } else {
            builder.append(item.toPrettyString());
        }
    }
}
