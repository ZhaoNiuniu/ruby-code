package io.github.mengru.agent.core.tool.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.core.task.TaskManager;

final class TeamToolSupport {

    private TeamToolSupport() {
    }

    static ObjectNode baseSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }

    static ObjectNode stringProperty(String description) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static String sender(java.util.Map<String, String> metadata) {
        String sender = metadata.get(TaskManager.AGENT_NAME_METADATA_KEY);
        return sender == null || sender.isBlank() ? "main" : sender.strip();
    }

    static JsonNode objectArgument(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? JsonNodeFactory.instance.objectNode() : value;
    }
}
