package io.github.mengru.agent.core.scheduler;

import java.time.Instant;

public record ScheduledJobView(
        ScheduledJob job,
        Instant nextRunAt,
        String queueStatus
) {
}
