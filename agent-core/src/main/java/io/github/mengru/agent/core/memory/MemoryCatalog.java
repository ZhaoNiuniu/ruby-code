package io.github.mengru.agent.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MemoryCatalog {

    public static final String MEMORY_DIR_NAME = ".memory";
    public static final String INDEX_FILE_NAME = "MEMORY.md";

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final Path workspace;
    private final List<MemoryDefinition> memories;
    private final List<String> warnings;

    private MemoryCatalog(Path workspace, List<MemoryDefinition> memories, List<String> warnings) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
        this.memories = List.copyOf(Objects.requireNonNull(memories, "memories must not be null"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
    }

    public static MemoryCatalog empty(Path workspace) {
        return new MemoryCatalog(workspace, List.of(), List.of());
    }

    public static MemoryCatalog scanDefault() {
        return scan(Path.of(""));
    }

    public static MemoryCatalog scan(Path workspace) {
        Path root = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
        Path memoryDir = root.resolve(MEMORY_DIR_NAME);
        if (!Files.isDirectory(memoryDir)) {
            return empty(root);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, MemoryDefinition> byName = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(memoryDir)) {
            List<Path> files = paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                parseMemory(root, file, warnings).ifPresent(memory -> {
                    if (byName.containsKey(memory.name())) {
                        warnings.add(file + ": duplicate memory name skipped: " + memory.name());
                    } else {
                        byName.put(memory.name(), memory);
                    }
                });
            }
        } catch (IOException e) {
            warnings.add("Failed to scan " + memoryDir + ": " + e.getMessage());
        }
        return new MemoryCatalog(root, List.copyOf(byName.values()), warnings);
    }

    public Path workspace() {
        return workspace;
    }

    public Path memoryDir() {
        return workspace.resolve(MEMORY_DIR_NAME);
    }

    public Path indexFile() {
        return workspace.resolve(INDEX_FILE_NAME);
    }

    public List<MemoryDefinition> memories() {
        return memories;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean isEmpty() {
        return memories.isEmpty();
    }

    public String renderIndex() {
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("# Memory Index\n\n");
        for (MemoryDefinition memory : memories) {
            builder.append(memory.linkLine(workspace)).append('\n');
        }
        return builder.toString().strip();
    }

    public MemoryDefinition find(String nameOrFile) {
        if (nameOrFile == null || nameOrFile.isBlank()) {
            return null;
        }
        String query = nameOrFile.strip();
        for (MemoryDefinition memory : memories) {
            if (memory.name().equals(query) || memory.path().getFileName().toString().equals(query)) {
                return memory;
            }
        }
        return null;
    }

    static java.util.Optional<MemoryDefinition> parseMemory(Path workspace, Path file, List<String> warnings) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add(file + ": failed to read memory: " + e.getMessage());
            return java.util.Optional.empty();
        }
        if (!content.startsWith("---\n")) {
            warnings.add(file + ": missing frontmatter");
            return java.util.Optional.empty();
        }
        int end = content.indexOf("\n---", 4);
        if (end < 0) {
            warnings.add(file + ": missing closing frontmatter");
            return java.util.Optional.empty();
        }
        Map<String, String> fields = parseFrontmatter(content.substring(4, end));
        String name = fields.get("name");
        String description = fields.get("description");
        String typeValue = fields.get("type");
        if (name == null || name.isBlank()) {
            warnings.add(file + ": missing name");
            return java.util.Optional.empty();
        }
        name = name.strip();
        if (!NAME_PATTERN.matcher(name).matches()) {
            warnings.add(file + ": invalid name: " + name);
            return java.util.Optional.empty();
        }
        if (description == null || description.isBlank()) {
            warnings.add(file + ": missing description");
            return java.util.Optional.empty();
        }
        java.util.Optional<MemoryType> type = MemoryType.from(typeValue);
        if (type.isEmpty()) {
            warnings.add(file + ": invalid type: " + typeValue);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new MemoryDefinition(
                name,
                description,
                type.get(),
                file.toAbsolutePath().normalize(),
                content
        ));
    }

    private static Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rawLine : frontmatter.split("\n")) {
            int colon = rawLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = rawLine.substring(0, colon).strip();
            String value = rawLine.substring(colon + 1).strip();
            if ("name".equals(key) || "description".equals(key) || "type".equals(key)) {
                fields.put(key, unquote(value));
            }
        }
        return fields;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
