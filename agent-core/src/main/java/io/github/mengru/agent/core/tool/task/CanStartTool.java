package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.CanStartResult;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Objects;

public final class CanStartTool implements Tool {

    public static final String NAME = "can_start";

    private final TaskManager manager;

    public CanStartTool(TaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Check whether a durable project task can be claimed based on dependency completion.";
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
            CanStartResult result = manager.canStart(request.stringArgument("taskId"));
            return ToolResult.success(manager.canStartToJson(result).toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
