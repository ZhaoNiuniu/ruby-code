package io.github.mengru.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    void defaultToolsContainsLocalTools() {
        ToolRegistry registry = ToolRegistry.defaultTools();

        assertThat(registry.findByName("echo")).isEmpty();
        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "bash", "read_file", "write_file", "edit_file", "glob");
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
                .containsExactly("todo_write", "subagent", "bash", "read_file", "write_file", "edit_file", "glob");
    }

    @Test
    void skillAwareDefaultToolsContainLoadSkillWhenCatalogIsNotEmpty() {
        ToolRegistry registry = ToolRegistry.defaultToolsWithSkills(skillCatalog());

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "load_skill", "bash", "read_file", "write_file", "edit_file", "glob");
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
                .containsExactly("todo_write", "load_skill", "subagent", "bash", "read_file", "write_file", "edit_file", "glob");
    }

    @Test
    void investigationToolsAreReadOnlyAndNonRecursive() {
        ToolRegistry registry = ToolRegistry.investigationTools();

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "read_file", "glob", "bash");
        assertThat(registry.findByName("write_file")).isEmpty();
        assertThat(registry.findByName("edit_file")).isEmpty();
        assertThat(registry.findByName("subagent")).isEmpty();
    }

    @Test
    void skillAwareInvestigationToolsContainLoadSkillButRemainNonRecursive() {
        ToolRegistry registry = ToolRegistry.investigationTools(skillCatalog());

        assertThat(registry.tools())
                .extracting(Tool::name)
                .containsExactly("todo_write", "load_skill", "read_file", "glob", "bash");
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
}
