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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRuntimePermissionTest {

    @TempDir
    Path workspace;

    @Test
    void teammatePermissionEscalatesThroughLeadReviewToHumanApproverAndInternalRuntimeResponse() {
        AtomicReference<PermissionRequest> seenRequest = new AtomicReference<>();
        try (TeamRuntime runtime = runtime((request, decision) -> {
            seenRequest.set(request);
            return true;
        }, request -> TeamPermissionReviewDecision.escalateToUser("request is aligned with teammate task"))) {
            ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("command", "ls");
            PermissionRequest request = new PermissionRequest("call_1", "bash", arguments, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "alice"));

            boolean approved = runtime.teammateApprover("alice")
                    .approve(request, PermissionDecision.askUser("bash executes a workspace command", "command=ls"));

            assertThat(approved).isTrue();
            assertThat(seenRequest.get().toolName()).isEqualTo(request.toolName());
            assertThat(seenRequest.get().metadata())
                    .containsEntry(TeamRuntime.PERMISSION_REVIEWER_METADATA_KEY, "main")
                    .containsEntry(TeamRuntime.PERMISSION_REVIEW_REASON_METADATA_KEY, "request is aligned with teammate task");
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
            assertThat(response.payload().path("reviewOutcome").asText()).isEqualTo("escalate_to_user");
            assertThat(response.payload().path("reviewReason").asText()).isEqualTo("request is aligned with teammate task");
        }
    }

    @Test
    void leadReviewDeniesWithoutCallingHumanApprover() {
        AtomicBoolean humanCalled = new AtomicBoolean();
        try (TeamRuntime runtime = runtime((request, decision) -> {
            humanCalled.set(true);
            return true;
        }, request -> TeamPermissionReviewDecision.deny("request is unrelated to assigned work"))) {
            ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("command", "curl https://example.com");
            PermissionRequest request = new PermissionRequest("call_2", "bash", arguments, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "alice"));

            boolean approved = runtime.teammateApprover("alice")
                    .approve(request, PermissionDecision.askUser("bash executes a workspace command", "command=curl https://example.com"));

            assertThat(approved).isFalse();
            assertThat(humanCalled).isFalse();
            List<TeamMessage> teammateMessages = runtime.bus().consume("alice");
            assertThat(teammateMessages).hasSize(1);
            TeamMessage response = teammateMessages.get(0);
            assertThat(response.payload().path("approved").asBoolean()).isFalse();
            assertThat(response.payload().path("reviewOutcome").asText()).isEqualTo("deny");
            assertThat(response.payload().path("reviewReason").asText()).isEqualTo("request is unrelated to assigned work");
        }
    }

    @Test
    void defaultLeadReviewDeniesUnknownTeammateBeforeHumanApprover() {
        AtomicBoolean humanCalled = new AtomicBoolean();
        try (TeamRuntime runtime = runtime((request, decision) -> {
            humanCalled.set(true);
            return true;
        })) {
            ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("command", "ls");
            PermissionRequest request = new PermissionRequest("call_3", "bash", arguments, Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "alice"));

            boolean approved = runtime.teammateApprover("alice")
                    .approve(request, PermissionDecision.askUser("bash executes a workspace command", "command=ls"));

            assertThat(approved).isFalse();
            assertThat(humanCalled).isFalse();
            List<TeamMessage> teammateMessages = runtime.bus().consume("alice");
            assertThat(teammateMessages).hasSize(1);
            assertThat(teammateMessages.get(0).payload().path("reviewReason").asText()).isEqualTo("unknown teammate: alice");
        }
    }

    private TeamRuntime runtime(io.github.mengru.agent.core.permission.UserApprover approver) {
        return runtime(approver, new DefaultTeamPermissionReviewer());
    }

    private TeamRuntime runtime(
            io.github.mengru.agent.core.permission.UserApprover approver,
            TeamPermissionReviewer reviewer
    ) {
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
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main"),
                reviewer
        );
    }
}
