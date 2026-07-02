package io.github.mengru.agent.core.scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public record ScheduledJob(
        String jobId,
        String name,
        String task,
        ScheduledJobType type,
        boolean durable,
        String cronExpression,
        Instant runAt,
        ZoneId zoneId
) {

    public ScheduledJob {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        zoneId = zoneId == null ? ZoneId.systemDefault() : zoneId;
        name = name == null ? "" : name.strip();
        cronExpression = cronExpression == null ? "" : cronExpression.strip();
        if (type == ScheduledJobType.CRON && cronExpression.isBlank()) {
            throw new IllegalArgumentException("cronExpression is required for cron jobs");
        }
        if (type == ScheduledJobType.ONCE && runAt == null) {
            throw new IllegalArgumentException("runAt is required for once jobs");
        }
    }

    public String displayName() {
        return name.isBlank() ? jobId : name;
    }
}
