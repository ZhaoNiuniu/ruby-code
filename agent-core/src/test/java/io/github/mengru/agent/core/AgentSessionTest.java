package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSessionTest {

    @Test
    void secondTurnReceivesPreviousSuccessfulQuestionAndAnswer() {
        List<AgentRequest> seenRequests = new ArrayList<>();
        Agent agent = request -> {
            seenRequests.add(request);
            return new AgentResult("answer for " + request.task(), List.of(AgentStep.finalAnswer("done")), true);
        };
        AgentSession session = new AgentSession(agent);

        session.run(AgentRequest.of("first"));
        session.run(AgentRequest.of("second"));

        assertThat(seenRequests.get(0).conversationHistory()).isEmpty();
        assertThat(seenRequests.get(1).conversationHistory()).containsExactly(
                ConversationMessage.user("first"),
                ConversationMessage.assistant("answer for first")
        );
    }

    @Test
    void compactsOldTurnsIntoMemoryAfterHistoryWindow() {
        AgentSession session = new AgentSession(request ->
                new AgentResult("answer " + request.task(), List.of(), true));

        for (int i = 0; i < 12; i++) {
            session.run(AgentRequest.of("task-" + i));
        }

        assertThat(session.conversationHistory()).hasSize(8);
        assertThat(session.conversationHistory().get(0)).isEqualTo(ConversationMessage.user("task-8"));
        assertThat(session.conversationHistory().get(7)).isEqualTo(ConversationMessage.assistant("answer task-11"));
        assertThat(session.memory().markdown()).contains("task-0");
        assertThat(session.memory().markdown()).contains("answer task-7");
    }

    @Test
    void incompleteResultsDoNotEnterHistory() {
        AtomicInteger calls = new AtomicInteger();
        AgentSession session = new AgentSession(request -> {
            if (calls.getAndIncrement() == 0) {
                return new AgentResult("not done", List.of(AgentStep.thought("still working")), false);
            }
            return new AgentResult("done", List.of(AgentStep.finalAnswer("done")), true);
        });

        session.run(AgentRequest.of("first"));
        session.run(AgentRequest.of("second"));

        assertThat(session.conversationHistory()).containsExactly(
                ConversationMessage.user("second"),
                ConversationMessage.assistant("done")
        );
    }

    @Test
    void toolStepsDoNotEnterNextTurnHistory() {
        AtomicReference<List<ConversationMessage>> secondTurnHistory = new AtomicReference<>();
        Agent agent = request -> {
            if ("second".equals(request.task())) {
                secondTurnHistory.set(request.conversationHistory());
            }
            return new AgentResult("final answer", List.of(
                    AgentStep.toolCall("call-1", "read_file", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()),
                    AgentStep.toolResult("call-1", "read_file", "secret intermediate trace"),
                    AgentStep.finalAnswer("final answer")
            ), true);
        };
        AgentSession session = new AgentSession(agent);

        session.run(AgentRequest.of("first"));
        session.run(AgentRequest.of("second"));

        assertThat(secondTurnHistory.get()).containsExactly(
                ConversationMessage.user("first"),
                ConversationMessage.assistant("final answer")
        );
        assertThat(secondTurnHistory.get())
                .extracting(ConversationMessage::content)
                .doesNotContain("secret intermediate trace");
    }

    @Test
    void clearRemovesHistory() {
        AgentSession session = new AgentSession(request -> new AgentResult("ok", List.of(), true));

        session.run(AgentRequest.of("first"));
        session.clear();

        assertThat(session.conversationHistory()).isEmpty();
        assertThat(session.memory().isEmpty()).isTrue();
    }

    @Test
    void compactedMemoryIsSentToNextTurn() {
        AtomicReference<String> seenMemory = new AtomicReference<>();
        Agent agent = request -> {
            if ("after-compact".equals(request.task())) {
                seenMemory.set(request.memory().markdown());
            }
            return new AgentResult("answer " + request.task(), List.of(), true);
        };
        AgentSession session = new AgentSession(agent);
        for (int i = 0; i < 12; i++) {
            session.run(AgentRequest.of("task-" + i));
        }

        session.run(AgentRequest.of("after-compact"));

        assertThat(seenMemory.get()).contains("task-0");
    }
}
