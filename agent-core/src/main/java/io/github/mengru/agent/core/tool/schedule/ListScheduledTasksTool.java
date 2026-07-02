package io.github.mengru.agent.core.tool.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.scheduler.ScheduledJobView;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;

import java.util.Objects;

public final class ListScheduledTasksTool implements Tool {

    public static final String NAME = "list_scheduled_tasks";

    private final ScheduledTaskManager manager;

    public ListScheduledTasksTool(ScheduledTaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "List scheduled tasks, including next run time, durable/session-only status, and queue status.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        ArrayNode jobs = JsonNodeFactory.instance.arrayNode();
        for (ScheduledJobView view : manager.listJobs()) {
            ObjectNode job = jobs.addObject();
            job.put("jobId", view.job().jobId());
            job.put("name", view.job().name());
            job.put("task", view.job().task());
            job.put("type", view.job().type().value());
            job.put("durable", view.job().durable());
            job.put("zoneId", view.job().zoneId().getId());
            job.put("nextRunAt", view.nextRunAt().toString());
            job.put("queueStatus", view.queueStatus());
            if (!view.job().cronExpression().isBlank()) {
                job.put("cronExpression", view.job().cronExpression());
            }
            if (view.job().runAt() != null) {
                job.put("runAt", view.job().runAt().toString());
            }
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("count", jobs.size());
        output.set("jobs", jobs);
        return ToolResult.success(output.toPrettyString());
    }
}
