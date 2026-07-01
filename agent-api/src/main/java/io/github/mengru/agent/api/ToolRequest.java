package io.github.mengru.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

public record ToolRequest(String toolName, JsonNode arguments, Map<String, String> metadata) {

    public ToolRequest(String toolName, String input, Map<String, String> metadata) {
        this(toolName, inputArgument(input), metadata);
    }

    public ToolRequest {
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

    private static ObjectNode inputArgument(String input) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("input", input == null ? "" : input);
        return arguments;
    }
}
