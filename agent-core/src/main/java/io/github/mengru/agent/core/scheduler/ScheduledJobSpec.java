package io.github.mengru.agent.core.scheduler;

import java.time.Instant;
import java.time.ZoneId;

public record ScheduledJobSpec(
        String name,
        String task,
        ScheduledJobType type,
        boolean durable,
        String cronExpression,
        Instant runAt,
        ZoneId zoneId
) {
}
