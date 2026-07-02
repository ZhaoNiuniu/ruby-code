package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.core.task.TaskDefinition;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.ArrayList;
import java.util.List;

final class TaskToolSupport {

    private TaskToolSupport() {
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

    static ObjectNode booleanProperty(String description) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    static ObjectNode statusProperty(String description) {
        ObjectNode property = stringProperty(description);
        property.putArray("enum").add("pending").add("in_progress").add("completed");
        return property;
    }

    static ObjectNode blockedByProperty() {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", "array");
        property.put("description", "Task ids that must be completed before this task can be claimed.");
        property.putObject("items").put("type", "string");
        return property;
    }

    static List<String> stringArray(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(name + "[" + i + "] must be a non-empty string");
            }
            values.add(item.asText().strip());
        }
        return values;
    }

    static Boolean optionalBoolean(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return value.asBoolean();
    }

    static String ownerFromMetadata(java.util.Map<String, String> metadata) {
        String owner = metadata.get(TaskManager.AGENT_NAME_METADATA_KEY);
        return owner == null || owner.isBlank() ? "main" : owner.strip();
    }

    static ArrayNode taskArray(TaskManager manager, List<TaskDefinition> tasks) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TaskDefinition task : tasks) {
            array.add(manager.toJson(task));
        }
        return array;
    }
}
