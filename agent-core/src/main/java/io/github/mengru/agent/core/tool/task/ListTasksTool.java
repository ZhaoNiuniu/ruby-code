package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.TaskDefinition;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Objects;

public final class ListTasksTool implements Tool {

    public static final String NAME = "list_tasks";

    private final TaskManager manager;

    public ListTasksTool(TaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List durable project tasks as compact summaries, optionally filtered by status, owner, or canStart.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TaskToolSupport.baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("status", TaskToolSupport.statusProperty("Optional status filter."));
        properties.set("owner", TaskToolSupport.stringProperty("Optional owner filter."));
        properties.set("canStart", TaskToolSupport.booleanProperty("Optional dependency readiness filter."));
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            TaskManager.ListTasksResult result = manager.list(
                    request.stringArgument("status"),
                    request.stringArgument("owner"),
                    TaskToolSupport.optionalBoolean(request.arguments(), "canStart")
            );
            ArrayNode tasks = JsonNodeFactory.instance.arrayNode();
            for (TaskManager.TaskSummary summary : result.tasks()) {
                TaskDefinition task = summary.task();
                ObjectNode node = tasks.addObject();
                node.put("id", task.id());
                node.put("subject", task.subject());
                node.put("status", task.status().value());
                if (task.owner().isBlank()) {
                    node.putNull("owner");
                } else {
                    node.put("owner", task.owner());
                }
                node.put("canStart", summary.canStart());
                node.put("blockedByCount", task.blockedBy().size());
            }
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("count", tasks.size());
            output.set("tasks", tasks);
            manager.appendWarnings(output, result.warnings());
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
