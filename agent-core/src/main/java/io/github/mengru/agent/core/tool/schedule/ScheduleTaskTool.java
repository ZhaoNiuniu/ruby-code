package io.github.mengru.agent.core.tool.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.scheduler.ScheduledJobSpec;
import io.github.mengru.agent.core.scheduler.ScheduledJobType;
import io.github.mengru.agent.core.scheduler.ScheduledJobView;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class ScheduleTaskTool implements Tool {

    public static final String NAME = "schedule_task";

    private final ScheduledTaskManager manager;

    public ScheduleTaskTool(ScheduledTaskManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Create a session-only or durable scheduled task. Cron jobs use a 6-field second-level expression; once jobs use runAt.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("task")
                .put("type", "string")
                .put("description", "Task to deliver to the agent when the schedule triggers.");
        properties.putObject("name")
                .put("type", "string")
                .put("description", "Optional human-readable name for display.");
        ObjectNode type = properties.putObject("type");
        type.put("type", "string");
        type.put("description", "Schedule type.");
        type.putArray("enum").add("cron").add("once");
        properties.putObject("cronExpression")
                .put("type", "string")
                .put("description", "Required for type=cron. Six fields: second minute hour day month weekday.");
        properties.putObject("runAt")
                .put("type", "string")
                .put("description", "Required for type=once. ISO-8601 time; local date-times use zoneId or system zone.");
        properties.putObject("zoneId")
                .put("type", "string")
                .put("description", "Optional IANA zone id, for example Asia/Shanghai. Defaults to system zone.");
        properties.putObject("durable")
                .put("type", "boolean")
                .put("description", "When true, write the job definition to .scheduled_tasks.json.");
        schema.putArray("required").add("task").add("type");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            String task = request.stringArgument("task").strip();
            if (task.isBlank()) {
                return ToolResult.failure("task must not be blank");
            }
            ScheduledJobType type = ScheduledJobType.from(request.stringArgument("type"));
            ZoneId zoneId = parseZone(request.stringArgument("zoneId"));
            boolean durable = booleanArgument(request.arguments(), "durable", false);
            ScheduledJobSpec spec = switch (type) {
                case CRON -> new ScheduledJobSpec(
                        request.stringArgument("name"),
                        task,
                        type,
                        durable,
                        request.stringArgument("cronExpression"),
                        null,
                        zoneId
                );
                case ONCE -> new ScheduledJobSpec(
                        request.stringArgument("name"),
                        task,
                        type,
                        durable,
                        "",
                        parseRunAt(request.stringArgument("runAt"), zoneId),
                        zoneId
                );
            };
            ScheduledJobView view = manager.schedule(spec);
            return ToolResult.success(render(view).toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private ObjectNode render(ScheduledJobView view) {
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("message", "scheduled task created");
        output.put("jobId", view.job().jobId());
        output.put("name", view.job().name());
        output.put("type", view.job().type().value());
        output.put("durable", view.job().durable());
        output.put("zoneId", view.job().zoneId().getId());
        output.put("nextRunAt", view.nextRunAt().toString());
        output.put("queueStatus", view.queueStatus());
        return output;
    }

    private static ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(value.strip());
    }

    private static Instant parseRunAt(String value, ZoneId zoneId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runAt is required for once jobs");
        }
        String text = value.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try offset/zoned/local formats below.
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try zoned/local formats below.
        }
        try {
            return ZonedDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try local format below.
        }
        return LocalDateTime.parse(text).atZone(zoneId).toInstant();
    }

    private static boolean booleanArgument(JsonNode arguments, String name, boolean defaultValue) {
        JsonNode value = arguments.get(name);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }
}
