package io.github.mengru.agent.core.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void missingOrEmptySkillsDirectoryReturnsEmptyCatalog() throws Exception {
        assertThat(SkillCatalog.scan(tempDir.resolve("missing")).isEmpty()).isTrue();

        Path skills = tempDir.resolve("skills");
        Files.createDirectories(skills);

        assertThat(SkillCatalog.scan(skills).isEmpty()).isTrue();
    }

    @Test
    void scansValidSkillFiles() throws Exception {
        Path skill = writeSkill("java-agent", "Java agent guidance", "Use Java 17.");

        SkillCatalog catalog = SkillCatalog.scan(tempDir.resolve("skills"));

        assertThat(catalog.isEmpty()).isFalse();
        assertThat(catalog.skills()).hasSize(1);
        assertThat(catalog.findByName("java-agent")).isPresent();
        SkillDefinition definition = catalog.findByName("java-agent").orElseThrow();
        assertThat(definition.description()).isEqualTo("Java agent guidance");
        assertThat(definition.path()).isEqualTo(skill.toAbsolutePath().normalize());
        assertThat(definition.content()).contains("Use Java 17.");
    }

    @Test
    void rejectsInvalidFrontmatter() throws Exception {
        Path bad = tempDir.resolve("skills/bad/SKILL.md");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "name: bad\n");

        assertThatThrownBy(() -> SkillCatalog.scan(tempDir.resolve("skills")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        Path missingDescription = tempDir.resolve("skills/missing-description/SKILL.md");
        Files.createDirectories(missingDescription.getParent());
        Files.writeString(missingDescription, """
                ---
                name: missing-description
                ---
                body
                """);

        assertThatThrownBy(() -> SkillCatalog.scan(tempDir.resolve("skills")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsDuplicateNames() throws Exception {
        writeSkill("same", "first", "one");
        Path duplicate = tempDir.resolve("skills/two/SKILL.md");
        Files.createDirectories(duplicate.getParent());
        Files.writeString(duplicate, """
                ---
                name: same
                description: second
                ---
                two
                """);

        assertThatThrownBy(() -> SkillCatalog.scan(tempDir.resolve("skills")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate skill name: same");
    }

    @Test
    void rejectsInvalidNames() throws Exception {
        Path invalid = tempDir.resolve("skills/invalid/SKILL.md");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, """
                ---
                name: ../escape
                description: invalid
                ---
                body
                """);

        assertThatThrownBy(() -> SkillCatalog.scan(tempDir.resolve("skills")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported characters");
    }

    private Path writeSkill(String name, String description, String body) throws Exception {
        Path skill = tempDir.resolve("skills").resolve(name).resolve("SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body));
        return skill;
    }
}
