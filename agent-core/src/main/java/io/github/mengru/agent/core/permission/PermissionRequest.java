package io.github.mengru.agent.core.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Map;
import java.util.Objects;

public record PermissionRequest(String toolCallId, String toolName, JsonNode arguments, Map<String, String> metadata) {

    public PermissionRequest {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (arguments == null) {
            arguments = JsonNodeFactory.instance.objectNode();
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        metadata = Map.copyOf(metadata);
    }

    public String stringArgument(String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? "" : value.asText();
    }
}
