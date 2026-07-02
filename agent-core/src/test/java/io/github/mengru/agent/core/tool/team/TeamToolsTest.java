package io.github.mengru.agent.core.tool.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.team.TeamMessage;
import io.github.mengru.agent.core.team.TeamRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void spawnTeammateCreatesNamedThreadAndListShowsIt() throws Exception {
        try (TeamRuntime runtime = runtime()) {
            SpawnTeammateTool spawn = new SpawnTeammateTool(runtime);
            ObjectNode args = JsonNodeFactory.instance.objectNode()
                    .put("name", "worker")
                    .put("role", "investigator")
                    .put("task", "inspect README");

            ToolResult result = spawn.execute(new ToolRequest(spawn.name(), args, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")));

            assertThat(result.success()).isTrue();
            JsonNode output = MAPPER.readTree(result.output());
            assertThat(output.path("name").asText()).isEqualTo("worker");
            ToolResult list = new ListTeammatesTool(runtime).execute(new ToolRequest("list_teammates", JsonNodeFactory.instance.objectNode(), Map.of()));
            assertThat(list.output()).contains("worker").contains("investigator");
        }
    }

    @Test
    void spawnTeammateRejectsDuplicateLeadNameAndLimit() {
        try (TeamRuntime runtime = runtime()) {
            assertThat(new SpawnTeammateTool(runtime).execute(request("spawn_teammate", JsonNodeFactory.instance.objectNode()
                    .put("name", "lead")
                    .put("role", "bad")
                    .put("task", "bad"))).success()).isFalse();

            for (int i = 1; i <= TeamRuntime.MAX_TEAMMATES; i++) {
                runtime.spawn("worker_" + i, "role", "task", "");
            }
            assertThatThrownBy(() -> runtime.spawn("worker_5", "role", "task", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("team limit");
        }
    }

    @Test
    void sendMessageUsesLeadAliasAndWritesInbox() {
        try (TeamRuntime runtime = runtime()) {
            SendMessageTool send = new SendMessageTool(runtime);
            ObjectNode args = JsonNodeFactory.instance.objectNode()
                    .put("to", "lead")
                    .put("type", "plain_text")
                    .put("content", "hello lead");

            ToolResult result = send.execute(new ToolRequest(send.name(), args, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "worker")));

            assertThat(result.success()).isTrue();
            List<TeamMessage> messages = runtime.consumeLeadMessages();
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).to()).isEqualTo(runtime.leadName());
            assertThat(messages.get(0).content()).isEqualTo("hello lead");
        }
    }

    private TeamRuntime runtime() {
        return new TeamRuntime(
                workspace,
                "main",
                (request, previousSteps, tools) -> AgentStep.finalAnswer("done"),
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                io.github.mengru.agent.api.ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }

    private static ToolRequest request(String toolName, ObjectNode arguments) {
        return new ToolRequest(toolName, arguments, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main"));
    }
}
