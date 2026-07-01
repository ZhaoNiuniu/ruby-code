package io.github.mengru.agent.core.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SkillCatalog {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final List<SkillDefinition> skills;
    private final Map<String, SkillDefinition> skillsByName;

    private SkillCatalog(Collection<SkillDefinition> skills) {
        LinkedHashMap<String, SkillDefinition> byName = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) {
            Objects.requireNonNull(skill, "skill must not be null");
            validateName(skill.name(), skill.path());
            SkillDefinition previous = byName.putIfAbsent(skill.name(), skill);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate skill name: " + skill.name());
            }
        }
        this.skills = List.copyOf(byName.values());
        this.skillsByName = Map.copyOf(byName);
    }

    public static SkillCatalog empty() {
        return new SkillCatalog(List.of());
    }

    public static SkillCatalog of(Collection<SkillDefinition> skills) {
        return new SkillCatalog(Objects.requireNonNull(skills, "skills must not be null"));
    }

    public static SkillCatalog scanDefault() {
        return scan(Path.of("").toAbsolutePath().normalize().resolve("skills"));
    }

    public static SkillCatalog scan(Path skillsRoot) {
        Path root = Objects.requireNonNull(skillsRoot, "skillsRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return empty();
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("skills path is not a directory: " + root);
        }

        List<Path> skillFiles;
        try (Stream<Path> stream = Files.walk(root)) {
            skillFiles = stream
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to scan skills directory: " + root + ": " + e.getMessage(), e);
        }
        if (skillFiles.isEmpty()) {
            return empty();
        }

        List<SkillDefinition> definitions = new ArrayList<>();
        for (Path skillFile : skillFiles) {
            definitions.add(parse(skillFile));
        }
        return new SkillCatalog(definitions);
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public List<SkillDefinition> skills() {
        return skills;
    }

    public Optional<SkillDefinition> findByName(String name) {
        return Optional.ofNullable(skillsByName.get(name));
    }

    private static SkillDefinition parse(Path skillFile) {
        String content;
        try {
            content = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read skill file: " + skillFile + ": " + e.getMessage(), e);
        }

        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !"---".equals(lines[0])) {
            throw new IllegalArgumentException("skill file must start with frontmatter delimiter: " + skillFile);
        }

        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i])) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException("skill frontmatter must end with delimiter: " + skillFile);
        }

        String name = null;
        String description = null;
        for (int i = 1; i < end; i++) {
            String line = lines[i].strip();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            String value = unquote(line.substring(separator + 1).strip());
            if ("name".equals(key)) {
                if (name != null) {
                    throw new IllegalArgumentException("skill frontmatter contains duplicate name: " + skillFile);
                }
                name = value;
            } else if ("description".equals(key)) {
                if (description != null) {
                    throw new IllegalArgumentException("skill frontmatter contains duplicate description: " + skillFile);
                }
                description = value;
            }
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill frontmatter requires non-empty name: " + skillFile);
        }
        validateName(name, skillFile);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("skill frontmatter requires non-empty description: " + skillFile);
        }
        return new SkillDefinition(name, description, skillFile, content);
    }

    private static void validateName(String name, Path path) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("skill name contains unsupported characters in " + path + ": " + name);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).strip();
            }
        }
        return value.strip();
    }
}
