package io.github.mengru.agent.core.task;

import java.util.List;
import java.util.Map;

public record TaskLoadResult(Map<String, TaskDefinition> tasksById, List<String> warnings) {

    public TaskLoadResult {
        tasksById = tasksById == null ? Map.of() : Map.copyOf(tasksById);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
