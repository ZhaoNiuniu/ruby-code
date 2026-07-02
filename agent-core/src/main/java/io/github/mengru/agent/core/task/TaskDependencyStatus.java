package io.github.mengru.agent.core.task;

public record TaskDependencyStatus(String taskId, boolean exists, String status, boolean completed) {
}
