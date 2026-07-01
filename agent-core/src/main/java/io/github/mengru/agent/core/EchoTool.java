package io.github.mengru.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

public final class EchoTool implements Tool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Returns the input unchanged with an echo prefix.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("type", "string");
        input.put("description", "Text to echo.");
        properties.set("input", input);

        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("input");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        return ToolResult.success("echo: " + request.stringArgument("input"));
    }
}
