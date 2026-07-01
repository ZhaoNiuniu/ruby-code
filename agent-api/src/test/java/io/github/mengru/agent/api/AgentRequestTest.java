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
}
