package io.github.mengru.agent.core.tool.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.util.Set;

public final class TodoWriteTool implements Tool {

    public static final String NAME = "todo_write";

    private static final Set<String> ALLOWED_STATUSES = Set.of("pending", "in_progress", "completed");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Create or replace the agent's current todo list for planning only. Does not read files, run commands, or perform work.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        ObjectNode todos = properties.putObject("todos");
        todos.put("type", "array");
        todos.put("description", "Complete replacement todo list. Use an empty array to clear the list.");

        ObjectNode item = todos.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        ObjectNode itemProperties = item.putObject("properties");
        itemProperties.putObject("content")
                .put("type", "string")
                .put("description", "Concrete task to track.");
        ObjectNode status = itemProperties.putObject("status");
        status.put("type", "string");
        status.put("description", "Current task status.");
        status.putArray("enum").add("pending").add("in_progress").add("completed");
        item.putArray("required").add("content").add("status");

        schema.putArray("required").add("todos");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode todos = request.arguments().get("todos");
        if (todos == null || !todos.isArray()) {
            return ToolResult.failure("todos must be an array");
        }

        ArrayNode normalizedTodos = JsonNodeFactory.instance.arrayNode();
        int inProgressCount = 0;
        for (int i = 0; i < todos.size(); i++) {
            JsonNode todo = todos.get(i);
            if (!todo.isObject()) {
                return ToolResult.failure("todos[" + i + "] must be an object");
            }

            JsonNode contentNode = todo.get("content");
            if (contentNode == null || !contentNode.isTextual() || contentNode.asText().isBlank()) {
                return ToolResult.failure("todos[" + i + "].content must be a non-empty string");
            }

            JsonNode statusNode = todo.get("status");
            if (statusNode == null || !statusNode.isTextual() || !ALLOWED_STATUSES.contains(statusNode.asText())) {
                return ToolResult.failure("todos[" + i + "].status must be one of pending, in_progress, completed");
            }

            String status = statusNode.asText();
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }

            ObjectNode normalizedTodo = JsonNodeFactory.instance.objectNode();
            normalizedTodo.put("content", contentNode.asText().strip());
            normalizedTodo.put("status", status);
            normalizedTodos.add(normalizedTodo);
        }

        if (inProgressCount > 1) {
            return ToolResult.failure("at most one todo can be in_progress");
        }

        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("message", "todo list updated");
        output.put("count", normalizedTodos.size());
        output.set("todos", normalizedTodos);
        return ToolResult.success(output.toPrettyString());
    }
}
