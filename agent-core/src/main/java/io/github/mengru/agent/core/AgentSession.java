package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ConversationMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentSession {

    public static final int DEFAULT_HISTORY_TURNS = 10;

    private final Agent agent;
    private final int maxHistoryTurns;
    private final List<ConversationMessage> conversationHistory = new ArrayList<>();

    public AgentSession(Agent agent) {
        this(agent, DEFAULT_HISTORY_TURNS);
    }

    public AgentSession(Agent agent, int maxHistoryTurns) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("maxHistoryTurns must be greater than zero");
        }
        this.maxHistoryTurns = maxHistoryTurns;
    }

    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRequest requestWithHistory = new AgentRequest(
                request.task(),
                request.maxSteps(),
                request.metadata(),
                request.systemPrompt(),
                conversationHistory
        );
        AgentResult result = agent.run(requestWithHistory);
        if (result.completed()) {
            conversationHistory.add(ConversationMessage.user(request.task()));
            conversationHistory.add(ConversationMessage.assistant(result.output()));
            trimHistory();
        }
        return result;
    }

    public List<ConversationMessage> conversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public void clear() {
        conversationHistory.clear();
    }

    private void trimHistory() {
        int maxMessages = maxHistoryTurns * 2;
        while (conversationHistory.size() > maxMessages) {
            conversationHistory.remove(0);
        }
    }
}
