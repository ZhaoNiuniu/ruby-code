package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelOptions;
import io.github.mengru.agent.core.AgentSession;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TeamInboxPollerTest {

    @TempDir
    Path workspace;

    @Test
    void permissionProtocolMessagesAreNotSubmittedToLeadModel() throws Exception {
        try (TeamRuntime runtime = runtime()) {
            AtomicInteger calls = new AtomicInteger();
            AgentSession session = new AgentSession(request -> {
                calls.incrementAndGet();
                return new AgentResult("ok", List.of(), true);
            });
            List<TeamExecutionResult> results = new CopyOnWriteArrayList<>();
            TeamInboxPoller poller = new TeamInboxPoller(
                    runtime,
                    session,
                    () -> Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main"),
                    "",
                    3,
                    ModelOptions.defaults(),
                    results::add,
                    Duration.ofMillis(10)
            );

            runtime.bus().send(TeamMessage.create(
                    runtime.teamId(),
                    TeamMessageType.PERMISSION_REQUEST,
                    "alice",
                    runtime.leadName(),
                    "permission requested",
                    "perm_test",
                    JsonNodeFactory.instance.objectNode()
            ));
            runtime.bus().send(TeamMessage.create(
                    runtime.teamId(),
                    TeamMessageType.STATUS_UPDATE,
                    "alice",
                    runtime.leadName(),
                    "work finished",
                    "",
                    JsonNodeFactory.instance.objectNode()
            ));

            poller.start();
            awaitCalls(calls, 1);
            poller.close();

            assertThat(calls).hasValue(1);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).messages()).extracting(TeamMessage::type)
                    .containsExactly(TeamMessageType.STATUS_UPDATE);
        }
    }

    @Test
    void visibleMessagesArrivingWithinDebounceWindowAreSubmittedTogether() throws Exception {
        try (TeamRuntime runtime = runtime()) {
            AtomicInteger calls = new AtomicInteger();
            AgentSession session = new AgentSession(request -> {
                calls.incrementAndGet();
                return new AgentResult("ok", List.of(), true);
            });
            List<TeamExecutionResult> results = new CopyOnWriteArrayList<>();
            TeamInboxPoller poller = new TeamInboxPoller(
                    runtime,
                    session,
                    () -> Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main"),
                    "",
                    3,
                    ModelOptions.defaults(),
                    results::add,
                    Duration.ofMillis(10),
                    Duration.ofMillis(120)
            );

            runtime.bus().send(TeamMessage.create(
                    runtime.teamId(),
                    TeamMessageType.STATUS_UPDATE,
                    "bob",
                    runtime.leadName(),
                    "summary",
                    "",
                    JsonNodeFactory.instance.objectNode()
            ));
            Thread delayedSecondMessage = new Thread(() -> {
                sleepQuietly(30);
                runtime.bus().send(TeamMessage.create(
                        runtime.teamId(),
                        TeamMessageType.STATUS_UPDATE,
                        "bob",
                        runtime.leadName(),
                        "details",
                        "",
                        JsonNodeFactory.instance.objectNode()
                ));
            });
            delayedSecondMessage.setDaemon(true);
            delayedSecondMessage.start();

            poller.start();
            awaitCalls(calls, 1);
            poller.close();

            assertThat(calls).hasValue(1);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).messages()).extracting(TeamMessage::content)
                    .containsExactly("summary", "details");
        }
    }

    private TeamRuntime runtime() {
        return new TeamRuntime(
                workspace,
                "main",
                (request, previousSteps, tools) -> io.github.mengru.agent.api.AgentStep.finalAnswer("done"),
                UserApprover.denyAll(),
                SkillCatalog.empty(),
                MemoryCatalog.empty(workspace),
                new TaskManager(workspace),
                ModelOptions.defaults(),
                "",
                Map.of(TaskManager.AGENT_NAME_METADATA_KEY, "main")
        );
    }

    private static void awaitCalls(AtomicInteger calls, int expected) throws InterruptedException {
        for (int i = 0; i < 50 && calls.get() < expected; i++) {
            Thread.sleep(20);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
