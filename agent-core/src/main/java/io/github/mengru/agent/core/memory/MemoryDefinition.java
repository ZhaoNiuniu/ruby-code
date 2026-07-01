package io.github.mengru.agent.core.memory;

import java.nio.file.Path;
import java.util.Objects;

public record MemoryDefinition(
        String name,
        String description,
        MemoryType type,
        Path path,
        String content
) {

    public MemoryDefinition {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(path, "path must not be null");
        content = content == null ? "" : content;
    }

    public String linkLine(Path workspace) {
        Path relative = workspace.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        return "- [" + name + "](" + relative + ")"
                + " type=" + type.value()
                + " - " + description;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
