package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.TaskDefinition;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Objects;

public final class ClaimTaskTool implements Tool {

    public static final String NAME = "claim_task";

    private final TaskManager manager;

    public ClaimTaskTool(TaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Claim a pending task for the current runtime agent after dependency checks pass.";
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
            TaskDefinition task = manager.claim(request.stringArgument("taskId"), TaskToolSupport.ownerFromMetadata(request.metadata()));
            ObjectNode output = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            output.put("message", "task claimed");
            output.set("task", manager.toJson(task));
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
