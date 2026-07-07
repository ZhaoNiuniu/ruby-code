package io.github.mengru.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeToolFilterTest {

    @TempDir
    Path workspace;

    private final RuntimeProfileResolver resolver = new RuntimeProfileResolver();

    @Test
    void readonlyHidesHighRiskToolsAndKeepsReadOnlyTools() {
        RuntimeSettings settings = resolver.resolve(workspace, "readonly", RuntimeProfileOverrides.none(), java.util.Map.of()).settings();
        ToolRegistry registry = ToolRegistry.of(List.of(
                new TestTool("todo_write"),
                new TestTool("read_file"),
                new TestTool("glob"),
                new TestTool("bash"),
                new TestTool("write_file"),
                new TestTool("edit_file"),
                new TestTool("subagent"),
                new TestTool("list_tasks"),
                new TestTool("get_task"),
                new TestTool("can_start"),
                new TestTool("create_task"),
                new TestTool("claim_task"),
                new TestTool("complete_task"),
                new TestTool("list_scheduled_tasks"),
                new TestTool("schedule_task"),
                new TestTool("cancel_scheduled_task"),
                new TestTool("list_teammates"),
                new TestTool("spawn_teammate"),
                new TestTool("send_message"),
                new TestTool("mcp__demo__echo")
        ));

        List<String> names = RuntimeToolFilter.filter(registry, settings).tools().stream().map(Tool::name).toList();

        assertThat(names).contains("todo_write", "read_file", "glob", "list_tasks", "get_task", "can_start", "list_scheduled_tasks", "list_teammates");
        assertThat(names).doesNotContain("bash", "write_file", "edit_file", "subagent", "create_task", "claim_task", "complete_task");
        assertThat(names).doesNotContain("schedule_task", "cancel_scheduled_task", "spawn_teammate", "send_message", "mcp__demo__echo");
    }

    @Test
    void devKeepsToolsWhenCategoriesAreEnabled() {
        RuntimeSettings settings = resolver.resolve(workspace, "dev", RuntimeProfileOverrides.none(), java.util.Map.of()).settings();
        ToolRegistry registry = ToolRegistry.of(List.of(new TestTool("bash"), new TestTool("subagent"), new TestTool("mcp__demo__echo")));

        List<String> names = RuntimeToolFilter.filter(registry, settings).tools().stream().map(Tool::name).toList();

        assertThat(names).containsExactly("bash", "subagent", "mcp__demo__echo");
    }

    private record TestTool(String name) implements Tool {

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode parametersSchema() {
            return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return ToolResult.success("ok");
        }
    }
}
