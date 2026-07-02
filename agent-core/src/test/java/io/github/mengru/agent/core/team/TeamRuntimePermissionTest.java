package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRuntimePermissionTest {

    @TempDir
    Path workspace;

    @Test
    void teammatePermissionUsesHumanApproverAndInternalRuntimeResponse() {
        AtomicReference<PermissionRequest> seenRequest = new AtomicReference<>();
        try (TeamRuntime runtime = runtime((request, decision) -> {
            seenRequest.set(request);
            return true;
        })) {
            ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("command", "ls");
            PermissionRequest request = new PermissionRequest("call_1", "bash", arguments, Map.of());

            boolean approved = runtime.teammateApprover("alice")
                    .approve(request, PermissionDecision.askUser("bash executes a workspace command", "command=ls"));

            assertThat(approved).isTrue();
            assertThat(seenRequest.get()).isEqualTo(request);
            List<TeamMessage> leadMessages = runtime.consumeLeadMessages();
            assertThat(leadMessages).hasSize(1);
            assertThat(leadMessages.get(0).type()).isEqualTo(TeamMessageType.PERMISSION_REQUEST);
            assertThat(leadMessages.get(0).correlationId()).startsWith("perm_");

            List<TeamMessage> teammateMessages = runtime.bus().consume("alice");
            assertThat(teammateMessages).hasSize(1);
            TeamMessage response = teammateMessages.get(0);
            assertThat(response.type()).isEqualTo(TeamMessageType.PERMISSION_RESPONSE);
            assertThat(response.correlationId()).isEqualTo(leadMessages.get(0).correlationId());
            assertThat(response.payload().path("approved").asBoolean()).isTrue();
        }
    }

    private TeamRuntime runtime(io.github.mengru.agent.core.permission.UserApprover approver) {
        return new TeamRuntime(
                workspace,
                "main",
                (request, previousSteps, tools) -> AgentStep.finalAnswer("done"),
                approver,
                SkillCatalog.empty(),
                MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                io.github.mengru.agent.api.ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }
}
