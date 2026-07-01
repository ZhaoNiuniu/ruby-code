package io.github.mengru.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCatalogTest {

    @TempDir
    Path workspace;

    @Test
    void missingMemoryDirectoryReturnsEmptyCatalog() {
        MemoryCatalog catalog = MemoryCatalog.scan(workspace);

        assertThat(catalog.isEmpty()).isTrue();
        assertThat(catalog.warnings()).isEmpty();
    }

    @Test
    void scansValidMemoryFiles() throws IOException {
        writeMemory("style.md", "java-style", "Java style preference", "user", "Use tabs.");

        MemoryCatalog catalog = MemoryCatalog.scan(workspace);

        assertThat(catalog.memories()).hasSize(1);
        assertThat(catalog.memories().get(0).name()).isEqualTo("java-style");
        assertThat(catalog.memories().get(0).type()).isEqualTo(MemoryType.USER);
        assertThat(catalog.renderIndex()).contains("[java-style]");
        assertThat(catalog.renderIndex()).contains("Java style preference");
    }

    @Test
    void skipsInvalidAndDuplicateMemoryFilesWithWarnings() throws IOException {
        Path memoryDir = workspace.resolve(".memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("bad.md"), "no frontmatter", StandardCharsets.UTF_8);
        writeMemory("invalid-type.md", "bad-type", "bad", "other", "content");
        writeMemory("one.md", "duplicate", "first", "user", "one");
        writeMemory("two.md", "duplicate", "second", "project", "two");

        MemoryCatalog catalog = MemoryCatalog.scan(workspace);

        assertThat(catalog.memories()).hasSize(1);
        assertThat(catalog.memories().get(0).description()).isEqualTo("first");
        assertThat(catalog.warnings()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(String.join("\n", catalog.warnings()))
                .contains("missing frontmatter")
                .contains("invalid type")
                .contains("duplicate memory name");
    }

    @Test
    void storeWritesUniqueFilesIndexAndGitIgnore() {
        MemoryStore store = new MemoryStore(workspace);

        MemoryDefinition first = store.save(new MemoryCandidate(
                "Use Tabs",
                "Indentation preference",
                MemoryType.USER,
                "Use tabs instead of spaces."
        ));
        MemoryDefinition second = store.save(new MemoryCandidate(
                "Use Tabs",
                "Another indentation preference",
                MemoryType.USER,
                "Tabs are still preferred."
        ));

        assertThat(first.name()).isEqualTo("use-tabs");
        assertThat(second.name()).isEqualTo("use-tabs-2");
        assertThat(workspace.resolve("MEMORY.md")).exists();
        assertThat(workspace.resolve(".gitignore")).content()
                .contains(".memory/")
                .contains("MEMORY.md");
        assertThat(workspace.resolve("MEMORY.md")).content()
                .contains("use-tabs")
                .contains("use-tabs-2");
    }

    private void writeMemory(String fileName, String name, String description, String type, String body) throws IOException {
        Path memoryDir = workspace.resolve(".memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(fileName), """
                ---
                name: %s
                description: %s
                type: %s
                ---
                
                %s
                """.formatted(name, description, type, body), StandardCharsets.UTF_8);
    }
}
