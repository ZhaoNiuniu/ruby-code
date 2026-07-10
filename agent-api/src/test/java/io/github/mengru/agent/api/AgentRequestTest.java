package io.github.mengru.agent.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRequestTest {

    @Test
    void createsDefaultRequest() {
        AgentRequest request = AgentRequest.of("summarize this");

        assertThat(request.task()).isEqualTo("summarize this");
        assertThat(request.maxSteps()).isEqualTo(8);
        assertThat(request.metadata()).isEmpty();
        assertThat(request.systemPrompt()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
        assertThat(request.memory().isEmpty()).isTrue();
        assertThat(request.modelOptions().maxOutputTokens()).isEqualTo(8192);
        assertThat(request.notifications()).isEmpty();
    }

    @Test
    void copiesMetadata() {
        Map<String, String> metadata = Map.of("traceId", "abc");

        AgentRequest request = new AgentRequest("run", 3, metadata);

        assertThat(request.metadata()).containsEntry("traceId", "abc");
        assertThatThrownBy(() -> request.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void supportsCustomSystemPrompt() {
        AgentRequest request = AgentRequest.of("run", "custom system");

        assertThat(request.systemPrompt()).isEqualTo("custom system");
    }

    @Test
    void copiesConversationHistory() {
        List<ConversationMessage> history = new ArrayList<>();
        history.add(ConversationMessage.user("first question"));

        AgentRequest request = new AgentRequest("run", 3, Map.of(), "system", history);
        history.add(ConversationMessage.assistant("late answer"));

        assertThat(request.conversationHistory())
                .containsExactly(ConversationMessage.user("first question"));
        assertThatThrownBy(() -> request.conversationHistory().add(ConversationMessage.assistant("other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void supportsExplicitMemory() {
        AgentRequest request = new AgentRequest(
                "run",
                3,
                Map.of(),
                "system",
                List.of(),
                AgentMemory.of("## Goal\n\nKeep going")
        );

        assertThat(request.memory().markdown()).contains("Keep going");
    }

    @Test
    void supportsExplicitModelOptions() {
        AgentRequest request = AgentRequest.of("run")
                .withModelOptions(new ModelOptions(1234));

        assertThat(request.modelOptions().maxOutputTokens()).isEqualTo(1234);
        assertThat(request.withTask("other").modelOptions().maxOutputTokens()).isEqualTo(1234);
    }

    @Test
    void copiesBackgroundTaskNotifications() {
        List<BackgroundTaskNotification> notifications = new ArrayList<>();
        notifications.add(new BackgroundTaskNotification("bg_1", "bash", "completed", "done"));

        AgentRequest request = AgentRequest.of("run").withNotifications(notifications);
        notifications.clear();

        assertThat(request.notifications()).hasSize(1);
        assertThatThrownBy(() -> request.notifications().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void createsTaskNotificationStepWithoutToolCallId() {
        AgentStep step = AgentStep.taskNotification(
                new BackgroundTaskNotification("bg_1", "bash", "completed", "done")
        );

        assertThat(step.type()).isEqualTo(AgentStep.Type.TASK_NOTIFICATION);
        assertThat(step.toolCallIdOptional()).isEmpty();
        assertThat(step.content()).contains("<task_notification");
        assertThat(step.content()).contains("bg_1");
    }

    @Test
    void agentStepCopiesMetadata() {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("provider.reasoning", "think");

        AgentStep step = AgentStep.finalAnswer("ok", metadata);
        metadata.clear();

        assertThat(step.metadata()).containsEntry("provider.reasoning", "think");
        assertThatThrownBy(() -> step.metadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentResultCopiesCompressionEvents() {
        List<ContextCompressionEvent> events = new ArrayList<>();
        events.add(new ContextCompressionEvent(
                ContextCompressionEvent.Stage.MICRO_COMPACT,
                "reason",
                10,
                5,
                "summary"
        ));

        AgentResult result = new AgentResult("ok", List.of(), true, events);
        events.clear();

        assertThat(result.compressionEvents()).hasSize(1);
        assertThatThrownBy(() -> result.compressionEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentResultCopiesRecoveryEvents() {
        List<AgentRecoveryEvent> events = new ArrayList<>();
        events.add(new AgentRecoveryEvent(
                ModelErrorCode.TRANSIENT,
                "retry",
                1,
                true,
                8192,
                8192,
                "ok"
        ));

        AgentResult result = new AgentResult("ok", List.of(), true, List.of(), events);
        events.clear();

        assertThat(result.recoveryEvents()).hasSize(1);
        assertThatThrownBy(() -> result.recoveryEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentResultCopiesTraceEvents() {
        List<TraceEvent> events = new ArrayList<>();
        events.add(TraceEvent.of(TraceEvent.Type.MODEL_CALL, Map.of("status", "start")));

        AgentResult result = new AgentResult("ok", List.of(), true, List.of(), List.of(), events);
        events.clear();

        assertThat(result.traceEvents()).hasSize(1);
        assertThatThrownBy(() -> result.traceEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentResultCopiesAuditEvents() {
        List<AuditEvent> events = new ArrayList<>();
        events.add(AuditEvent.of(
                "run_1",
                1,
                AuditEvent.Type.MODEL_CALL,
                "lead:main",
                "main",
                "",
                "",
                Map.of("status", "start")
        ));

        AgentResult result = new AgentResult("ok", List.of(), true, List.of(), List.of(), List.of(), "run_1", events);
        events.clear();

        assertThat(result.runId()).isEqualTo("run_1");
        assertThat(result.auditEvents()).hasSize(1);
        assertThatThrownBy(() -> result.auditEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void promptTooLongExceptionIsTypedModelException() {
        PromptTooLongException exception = new PromptTooLongException("too long");

        assertThat(exception.code()).isEqualTo(ModelErrorCode.PROMPT_TOO_LONG);
    }
}
