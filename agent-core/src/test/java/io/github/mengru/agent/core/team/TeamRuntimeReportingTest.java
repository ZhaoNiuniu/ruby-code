package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRuntimeReportingTest {

    @TempDir
    Path workspace;

    @Test
    void teammateManualLeadMessageSuppressesAutomaticCompletedReport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (request, previousSteps, tools) -> {
            if (calls.getAndIncrement() == 0) {
                ObjectNode arguments = JsonNodeFactory.instance.objectNode()
                        .put("to", "lead")
                        .put("type", "status_update")
                        .put("content", "manual teammate report");
                return AgentStep.toolCall("call_send", "send_message", arguments);
            }
            return AgentStep.finalAnswer("automatic report should be suppressed");
        };

        try (TeamRuntime runtime = runtime(model)) {
            runtime.spawn("alice", "tester", "check the file", "");

            List<TeamMessage> messages = awaitLeadMessages(runtime, 1);
            Thread.sleep(300);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).content()).isEqualTo("manual teammate report");
            assertThat(runtime.consumeLeadMessages()).isEmpty();
        }
    }

    private TeamRuntime runtime(ModelClient modelClient) {
        return new TeamRuntime(
                workspace,
                "main",
                modelClient,
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                io.github.mengru.agent.api.ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }

    private static List<TeamMessage> awaitLeadMessages(TeamRuntime runtime, int expected) throws InterruptedException {
        ArrayList<TeamMessage> messages = new ArrayList<>();
        for (int i = 0; i < 80 && messages.size() < expected; i++) {
            messages.addAll(runtime.consumeLeadMessages());
            if (messages.size() >= expected) {
                return List.copyOf(messages);
            }
            Thread.sleep(50);
        }
        return List.copyOf(messages);
    }
}
