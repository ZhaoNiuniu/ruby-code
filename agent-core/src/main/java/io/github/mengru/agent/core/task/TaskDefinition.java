package io.github.mengru.agent.core.task;

import java.util.List;
import java.util.Objects;

public record TaskDefinition(
        String id,
        String subject,
        String description,
        TaskStatus status,
        String owner,
        List<String> blockedBy
) {

    public TaskDefinition {
        id = requireNonBlank(id, "id");
        subject = requireNonBlank(subject, "subject");
        description = requireNonBlank(description, "description");
        status = Objects.requireNonNull(status, "status must not be null");
        owner = owner == null || owner.isBlank() ? "" : owner.strip();
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        for (String dependency : blockedBy) {
            requireNonBlank(dependency, "blockedBy dependency");
        }
    }

    public TaskDefinition withStatus(TaskStatus newStatus, String newOwner) {
        return new TaskDefinition(id, subject, description, newStatus, newOwner, blockedBy);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return stripped;
    }
}
