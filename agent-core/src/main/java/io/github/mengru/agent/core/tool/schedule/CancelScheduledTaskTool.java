package io.github.mengru.agent.core.tool.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;

import java.util.Objects;

public final class CancelScheduledTaskTool implements Tool {

    public static final String NAME = "cancel_scheduled_task";

    private final ScheduledTaskManager manager;

    public CancelScheduledTaskTool(ScheduledTaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Cancel a scheduled task by jobId. Durable jobs are removed from .scheduled_tasks.json.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("jobId")
                .put("type", "string")
                .put("description", "Scheduled job id returned by schedule_task.");
        schema.putArray("required").add("jobId");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String jobId = request.stringArgument("jobId").strip();
        if (jobId.isBlank()) {
            return ToolResult.failure("jobId must not be blank");
        }
        boolean cancelled = manager.cancel(jobId);
        if (!cancelled) {
            return ToolResult.failure("scheduled task not found: " + jobId);
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("message", "scheduled task cancelled");
        output.put("jobId", jobId);
        return ToolResult.success(output.toPrettyString());
    }
}
