package io.github.mengru.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.scheduler.CronQueue;
import io.github.mengru.agent.core.scheduler.ScheduledJobStore;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @TempDir
    Path workspace;

    @Test
    void defaultToolsContainsLocalTools() {
        ToolRegistry registry = ToolRegistry.defaultTools();

        assertThat(registry.findByName("echo")).isEmpty();
        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly(
                        "todo_write",
                        "list_tasks",
                        "get_task",
                        "can_start",
                        "create_task",
                        "claim_task",
                        "complete_task",
                        "bash",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob"
                );
    }

    @Test
    void builderRegistersCustomTool() {
        ToolRegistry registry = ToolRegistry.builder()
                .add(new TestTool("custom"))
                .build();

        assertThat(registry.findByName("custom")).isPresent();
        assertThat(registry.findByName("missing")).isEmpty();
    }

    @Test
    void runtimeDefaultToolsContainSubagent() {
        ToolRegistry registry = ToolRegistry.defaultToolsWithSubagent(
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                UserApprover.denyAll()
        );

        assertThat(registry.tools())
                .extracting(Tool::name)
                .contains(
                        "todo_write",
                        "list_tasks",
                        "get_task",
                        "can_start",
                        "create_task",
                        "claim_task",
                        "complete_task",
                        "subagent",
                        "bash",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob"
                );
    }

    @Test
    void skillAwareDefaultToolsContainLoadSkillWhenCatalogIsNotEmpty() {
        ToolRegistry registry = ToolRegistry.defaultToolsWithSkills(skillCatalog());

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly(
                        "todo_write",
                        "load_skill",
                        "list_tasks",
                        "get_task",
                        "can_start",
                        "create_task",
                        "claim_task",
                        "complete_task",
                        "bash",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob"
                );
    }

    @Test
    void skillAwareRuntimeDefaultToolsContainLoadSkillAndSubagent() {
        ToolRegistry registry = ToolRegistry.defaultToolsWithSubagent(
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                UserApprover.denyAll(),
                skillCatalog()
        );

        assertThat(registry.tools())
                .extracting(Tool::name)
                .contains(
                        "todo_write",
                        "load_skill",
                        "list_tasks",
                        "get_task",
                        "can_start",
                        "create_task",
                        "claim_task",
                        "complete_task",
                        "subagent",
                        "bash",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "glob"
                );
    }

    @Test
    void runtimeDefaultToolsCanContainSchedulerTools() {
        ScheduledTaskManager manager = new ScheduledTaskManager(new ScheduledJobStore(workspace), new CronQueue());

        ToolRegistry registry = ToolRegistry.defaultToolsWithSubagent(
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                io.github.mengru.agent.core.memory.MemoryCatalog.empty(workspace),
                manager
        );

        assertThat(registry.tools())
                .extracting(Tool::name)
                .contains(
                        "schedule_task",
                        "list_scheduled_tasks",
                        "cancel_scheduled_task"
                );
    }

    @Test
    void chatRuntimeToolsCanContainTeamTools() {
        try (TeamRuntime teamRuntime = teamRuntime()) {
            ToolRegistry registry = ToolRegistry.defaultToolsWithSubagent(
                    (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                    UserApprover.denyAll(),
                    SkillCatalog.empty(),
                    io.github.mengru.agent.core.memory.MemoryCatalog.empty(workspace),
                    null,
                    new TaskManager(workspace),
                    teamRuntime
            );

            assertThat(registry.tools())
                    .extracting(Tool::name)
                    .contains("spawn_teammate", "send_message", "list_teammates");
        }
    }

    @Test
    void teammateToolsExcludeRecursiveAndSchedulerTools() {
        try (TeamRuntime teamRuntime = teamRuntime()) {
            ToolRegistry registry = ToolRegistry.teammateTools(teamRuntime, new TaskManager(workspace));

            assertThat(registry.tools())
                    .extracting(Tool::name)
                    .contains(
                            "send_message",
                            "list_tasks",
                            "get_task",
                            "can_start",
                            "create_task",
                            "claim_task",
                            "complete_task",
                            "bash",
                            "read_file",
                            "write_file",
                            "edit_file",
                            "glob"
                    );
            assertThat(registry.findByName("spawn_teammate")).isEmpty();
            assertThat(registry.findByName("list_teammates")).isEmpty();
            assertThat(registry.findByName("subagent")).isEmpty();
            assertThat(registry.findByName("schedule_task")).isEmpty();
        }
    }

    @Test
    void investigationToolsAreReadOnlyAndNonRecursive() {
        ToolRegistry registry = ToolRegistry.investigationTools();

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "list_tasks", "get_task", "can_start", "read_file", "glob", "bash");
        assertThat(registry.findByName("create_task")).isEmpty();
        assertThat(registry.findByName("claim_task")).isEmpty();
        assertThat(registry.findByName("complete_task")).isEmpty();
        assertThat(registry.findByName("write_file")).isEmpty();
        assertThat(registry.findByName("edit_file")).isEmpty();
        assertThat(registry.findByName("subagent")).isEmpty();
    }

    @Test
    void skillAwareInvestigationToolsContainLoadSkillButRemainNonRecursive() {
        ToolRegistry registry = ToolRegistry.investigationTools(skillCatalog());

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "load_skill", "list_tasks", "get_task", "can_start", "read_file", "glob", "bash");
        assertThat(registry.findByName("create_task")).isEmpty();
        assertThat(registry.findByName("claim_task")).isEmpty();
        assertThat(registry.findByName("complete_task")).isEmpty();
        assertThat(registry.findByName("write_file")).isEmpty();
        assertThat(registry.findByName("edit_file")).isEmpty();
        assertThat(registry.findByName("subagent")).isEmpty();
    }

    @Test
    void duplicateToolNamesFailFast() {
        Tool first = new TestTool("same");
        Tool second = new TestTool("same");

        assertThatThrownBy(() -> ToolRegistry.builder().add(first).add(second).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate tool name: same");
    }

    private record TestTool(String name) implements Tool {

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return ToolResult.success("ok");
        }
    }

    private static SkillCatalog skillCatalog() {
        return SkillCatalog.of(List.of(new SkillDefinition(
                "java-agent",
                "Java agent guidance",
                Path.of("skills/java-agent/SKILL.md"),
                "content"
        )));
    }

    private TeamRuntime teamRuntime() {
        return new TeamRuntime(
                workspace,
                "main",
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok"),
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                io.github.mengru.agent.core.memory.MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                io.github.mengru.agent.api.ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }
}
