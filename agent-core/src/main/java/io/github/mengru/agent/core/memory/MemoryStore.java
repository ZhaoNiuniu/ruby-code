package io.github.mengru.agent.core.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

public final class MemoryStore {

    private final Path workspace;

    public MemoryStore(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
    }

    public static MemoryStore defaultStore() {
        return new MemoryStore(Path.of(""));
    }

    public MemoryCatalog catalog() {
        return MemoryCatalog.scan(workspace);
    }

    public MemoryDefinition save(MemoryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        try {
            Files.createDirectories(memoryDir());
            ensureGitIgnore();
            String name = uniqueName(slug(candidate.name()));
            Path path = memoryDir().resolve(name + ".md");
            String content = renderMemory(name, candidate);
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            MemoryCatalog catalog = MemoryCatalog.scan(workspace);
            Files.writeString(indexFile(), catalog.renderIndex() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return catalog.find(name);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save memory: " + e.getMessage(), e);
        }
    }

    public void ensureGitIgnore() {
        Path gitIgnore = workspace.resolve(".gitignore");
        String required = ".memory/\nMEMORY.md\n";
        try {
            if (!Files.exists(gitIgnore)) {
                Files.writeString(gitIgnore, required, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            String current = Files.readString(gitIgnore, StandardCharsets.UTF_8);
            String updated = current;
            if (!hasLine(current, ".memory/")) {
                updated = appendLine(updated, ".memory/");
            }
            if (!hasLine(updated, "MEMORY.md")) {
                updated = appendLine(updated, "MEMORY.md");
            }
            if (!updated.equals(current)) {
                Files.writeString(gitIgnore, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update .gitignore: " + e.getMessage(), e);
        }
    }

    private Path memoryDir() {
        return workspace.resolve(MemoryCatalog.MEMORY_DIR_NAME);
    }

    private Path indexFile() {
        return workspace.resolve(MemoryCatalog.INDEX_FILE_NAME);
    }

    private String uniqueName(String base) {
        String candidate = base;
        int suffix = 2;
        while (Files.exists(memoryDir().resolve(candidate + ".md"))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String renderMemory(String name, MemoryCandidate candidate) {
        return """
                ---
                name: %s
                description: %s
                type: %s
                ---
                
                %s
                """.formatted(name, candidate.description(), candidate.type().value(), candidate.content().strip());
    }

    static String slug(String value) {
        String normalized = (value == null ? "" : value)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (normalized.isBlank()) {
            return "memory";
        }
        if (!Character.isLetterOrDigit(normalized.charAt(0))) {
            return "memory-" + normalized;
        }
        return normalized;
    }

    private static boolean hasLine(String content, String expected) {
        for (String line : content.split("\n")) {
            if (line.strip().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String appendLine(String content, String line) {
        String prefix = content.endsWith("\n") || content.isBlank() ? content : content + "\n";
        return prefix + line + "\n";
    }
}
