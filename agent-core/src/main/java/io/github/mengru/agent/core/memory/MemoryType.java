package io.github.mengru.agent.core.memory;

import java.util.Locale;
import java.util.Optional;

public enum MemoryType {
    USER("user"),
    FEEDBACK("feedback"),
    PROJECT("project"),
    REFERENCE("reference");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<MemoryType> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (MemoryType type : values()) {
            if (type.value.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
