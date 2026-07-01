package io.github.mengru.agent.core.memory;

import java.util.Objects;

public record MemoryCandidate(String name, String description, MemoryType type, String content) {

    public MemoryCandidate {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        Objects.requireNonNull(type, "type must not be null");
        content = requireNonBlank(content, "content");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
