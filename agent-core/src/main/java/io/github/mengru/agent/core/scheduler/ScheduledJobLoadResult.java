package io.github.mengru.agent.core.scheduler;

import java.util.List;

public record ScheduledJobLoadResult(List<ScheduledJob> jobs, List<String> warnings) {

    public ScheduledJobLoadResult {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
