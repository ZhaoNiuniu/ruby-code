package io.github.mengru.agent.core.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskManagerTest {

    @TempDir
    Path workspace;

    @Test
    void createsSequentialIdsAndDoesNotReuseDeletedIds() throws IOException {
        TaskManager manager = new TaskManager(workspace);

        TaskDefinition first = manager.create("Schema", "Define task JSON schema.", List.of());
        Files.delete(workspace.resolve(".tasks").resolve(first.id() + ".json"));
        TaskDefinition second = manager.create("API", "Build task tools.", List.of());

        assertThat(first.id()).isEqualTo("task_000001");
        assertThat(second.id()).isEqualTo("task_000002");
        assertThat(Files.readString(workspace.resolve(".tasks").resolve(".highwatermark"), StandardCharsets.UTF_8))
                .isEqualTo("2\n");
    }

    @Test
    void missingDependencyBlocksClaimWithoutCrashing() {
        TaskManager manager = new TaskManager(workspace);
        TaskDefinition task = manager.create("API", "Build API.", List.of("task_000999"));

        CanStartResult result = manager.canStart(task.id());

        assertThat(result.canStart()).isFalse();
        assertThat(result.reason()).contains("task_000999");
        assertThat(result.dependencies()).containsExactly(new TaskDependencyStatus("task_000999", false, "missing", false));
        assertThatThrownBy(() -> manager.claim(task.id(), "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void claimAndCompleteUnlockDownstreamTasks() {
        TaskManager manager = new TaskManager(workspace);
        TaskDefinition schema = manager.create("Schema", "Define schema.", List.of());
        TaskDefinition api = manager.create("API", "Build API.", List.of(schema.id()));
        TaskDefinition docs = manager.create("Docs", "Document API.", List.of(schema.id()));

        TaskDefinition claimed = manager.claim(schema.id(), "agent-a");
        TaskManager.CompleteTaskResult completed = manager.complete(schema.id(), "agent-a");

        assertThat(claimed.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(claimed.owner()).isEqualTo("agent-a");
        assertThat(completed.task().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.unlockedTasks())
                .extracting(TaskDefinition::id)
                .containsExactly(api.id(), docs.id());
        assertThat(manager.canStart(api.id()).canStart()).isTrue();
    }

    @Test
    void completeRequiresCurrentOwner() {
        TaskManager manager = new TaskManager(workspace);
        TaskDefinition task = manager.create("Schema", "Define schema.", List.of());
        manager.claim(task.id(), "agent-a");

        assertThatThrownBy(() -> manager.complete(task.id(), "agent-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner mismatch");
    }

    @Test
    void skipsBadTaskFilesWithWarnings() throws IOException {
        Path tasks = workspace.resolve(".tasks");
        Files.createDirectories(tasks);
        Files.writeString(tasks.resolve("bad.json"), "{\"id\":\"not-a-task\"}", StandardCharsets.UTF_8);
        TaskManager manager = new TaskManager(workspace);

        TaskLoadResult result = manager.load();

        assertThat(result.tasksById()).isEmpty();
        assertThat(result.warnings()).singleElement().asString().contains("Skipped invalid task file");
    }

    @Test
    void invalidHighWatermarkFailsWritesClearly() throws IOException {
        Path tasks = workspace.resolve(".tasks");
        Files.createDirectories(tasks);
        Files.writeString(tasks.resolve(".highwatermark"), "oops", StandardCharsets.UTF_8);
        TaskManager manager = new TaskManager(workspace);

        assertThatThrownBy(() -> manager.create("Schema", "Define schema.", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".tasks/.highwatermark");
    }
}
