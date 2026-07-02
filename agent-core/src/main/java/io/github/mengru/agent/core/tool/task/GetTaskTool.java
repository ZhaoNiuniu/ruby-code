package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Objects;

public final class GetTaskTool implements Tool {

    public static final String NAME = "get_task";

    private final TaskManager manager;

    public GetTaskTool(TaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Return the full durable task JSON and dependency readiness details.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TaskToolSupport.baseSchema();
        ((ObjectNode) schema.get("properties")).set("taskId", TaskToolSupport.stringProperty("Task id, for example task_000001."));
        schema.putArray("required").add("taskId");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            TaskManager.TaskDetail detail = manager.get(request.stringArgument("taskId"));
            ObjectNode output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            output.set("task", manager.toJson(detail.task()));
            output.set("canStart", manager.canStartToJson(detail.canStart()));
            manager.appendWarnings(output, detail.warnings());
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
