package io.github.mengru.agent.core.scheduler;

import java.time.Instant;
import java.util.Objects;

public record CronTriggeredTask(ScheduledJob job, Instant triggeredAt) {

    public CronTriggeredTask {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
    }

    public String asAgentTask() {
        return """
                <cron_trigger job_id="%s" name="%s" type="%s" durable="%s" triggered_at="%s">
                <task>
                %s
                </task>
                </cron_trigger>
                """.formatted(
                escapeAttribute(job.jobId()),
                escapeAttribute(job.name()),
                job.type().value(),
                Boolean.toString(job.durable()),
                triggeredAt,
                escapeText(job.task())
        ).strip();
    }

    private static String escapeAttribute(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapeText(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
