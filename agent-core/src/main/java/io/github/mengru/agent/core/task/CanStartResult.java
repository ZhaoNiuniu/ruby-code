package io.github.mengru.agent.core.task;

import java.util.List;

public record CanStartResult(
        String taskId,
        boolean canStart,
        String reason,
        List<TaskDependencyStatus> dependencies,
        List<String> warnings
) {

    public CanStartResult {
        reason = reason == null ? "" : reason;
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
