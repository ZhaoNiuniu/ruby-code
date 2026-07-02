package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.TaskDefinition;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Objects;

public final class CreateTaskTool implements Tool {

    public static final String NAME = "create_task";

    private final TaskManager manager;

    public CreateTaskTool(TaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Create a durable project task in .tasks/{id}.json with optional dependencies.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TaskToolSupport.baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("subject", TaskToolSupport.stringProperty("Short task title."));
        properties.set("description", TaskToolSupport.stringProperty("Full task description needed to resume work across sessions."));
        properties.set("blockedBy", TaskToolSupport.blockedByProperty());
        schema.putArray("required").add("subject").add("description");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            String subject = request.stringArgument("subject").strip();
            String description = request.stringArgument("description").strip();
            if (subject.isBlank()) {
                return ToolResult.failure("subject must not be blank");
            }
            if (description.isBlank()) {
                return ToolResult.failure("description must not be blank");
            }
            TaskDefinition task = manager.create(subject, description, TaskToolSupport.stringArray(request.arguments(), "blockedBy"));
            ObjectNode output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            output.put("message", "task created");
            output.set("task", manager.toJson(task));
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
