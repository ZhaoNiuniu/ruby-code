package io.github.mengru.agent.core.tool.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void createListGetAndCanStartUseStructuredTaskJson() throws Exception {
        TaskManager manager = new TaskManager(workspace);
        CreateTaskTool create = new CreateTaskTool(manager);

        ToolResult created = create.execute(request(CreateTaskTool.NAME, args()
                .put("subject", "Schema")
                .put("description", "Define schema.")));

        assertThat(created.success()).isTrue();
        JsonNode createdJson = MAPPER.readTree(created.output());
        assertThat(createdJson.at("/task/id").asText()).isEqualTo("task_000001");

        ToolResult listed = new ListTasksTool(manager).execute(request(ListTasksTool.NAME, args()));
        JsonNode listedJson = MAPPER.readTree(listed.output());
        assertThat(listedJson.at("/count").asInt()).isEqualTo(1);
        assertThat(listedJson.at("/tasks/0/subject").asText()).isEqualTo("Schema");

        ToolResult detail = new GetTaskTool(manager).execute(request(GetTaskTool.NAME, args().put("taskId", "task_000001")));
        assertThat(MAPPER.readTree(detail.output()).at("/task/description").asText()).isEqualTo("Define schema.");

        ToolResult canStart = new CanStartTool(manager).execute(request(CanStartTool.NAME, args().put("taskId", "task_000001")));
        assertThat(MAPPER.readTree(canStart.output()).at("/canStart").asBoolean()).isTrue();
    }

    @Test
    void claimUsesRuntimeAgentNameMetadataAndCompleteReportsUnlockedTasks() throws Exception {
        TaskManager manager = new TaskManager(workspace);
        new CreateTaskTool(manager).execute(request(CreateTaskTool.NAME, args()
                .put("subject", "Schema")
                .put("description", "Define schema.")));
        ObjectNode dependent = args().put("subject", "API").put("description", "Build API.");
        dependent.putArray("blockedBy").add("task_000001");
        new CreateTaskTool(manager).execute(request(CreateTaskTool.NAME, dependent));

        ToolResult claim = new ClaimTaskTool(manager).execute(request(
                ClaimTaskTool.NAME,
                args().put("taskId", "task_000001"),
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "agent-a")
        ));
        ToolResult complete = new CompleteTaskTool(manager).execute(request(
                CompleteTaskTool.NAME,
                args().put("taskId", "task_000001"),
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "agent-a")
        ));

        assertThat(claim.success()).isTrue();
        assertThat(MAPPER.readTree(claim.output()).at("/task/owner").asText()).isEqualTo("agent-a");
        assertThat(complete.success()).isTrue();
        JsonNode completedJson = MAPPER.readTree(complete.output());
        assertThat(completedJson.at("/unlockedTasks/0/id").asText()).isEqualTo("task_000002");
    }

    @Test
    void completeRejectsWrongOwner() {
        TaskManager manager = new TaskManager(workspace);
        new CreateTaskTool(manager).execute(request(CreateTaskTool.NAME, args()
                .put("subject", "Schema")
                .put("description", "Define schema.")));
        new ClaimTaskTool(manager).execute(request(
                ClaimTaskTool.NAME,
                args().put("taskId", "task_000001"),
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "agent-a")
        ));

        ToolResult result = new CompleteTaskTool(manager).execute(request(
                CompleteTaskTool.NAME,
                args().put("taskId", "task_000001"),
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "agent-b")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("owner mismatch");
    }

    private static ToolRequest request(String toolName, ObjectNode arguments) {
        return request(toolName, arguments, Map.of());
    }

    private static ToolRequest request(String toolName, ObjectNode arguments, Map<String, String> metadata) {
        return new ToolRequest(toolName, arguments, metadata);
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
