package io.github.mengru.agent.core.audit;

import java.util.Objects;

public record RunAuditSummary(
        String runId,
        String parentRunId,
        String actor,
        String trigger,
        String startedAt,
        String endedAt,
        String status,
        String summary
) {

    public RunAuditSummary {
        Objects.requireNonNull(runId, "runId must not be null");
        parentRunId = parentRunId == null ? "" : parentRunId;
        actor = actor == null ? "" : actor;
        trigger = trigger == null ? "" : trigger;
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
        status = status == null ? "" : status;
        summary = summary == null ? "" : summary;
    }
}
