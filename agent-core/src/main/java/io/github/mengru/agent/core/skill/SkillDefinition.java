package io.github.mengru.agent.core.skill;

import java.nio.file.Path;
import java.util.Objects;

public record SkillDefinition(String name, String description, Path path, String content) {

    public SkillDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("skill description must not be blank");
        }
        path = path.toAbsolutePath().normalize();
        description = description.strip();
    }
}
