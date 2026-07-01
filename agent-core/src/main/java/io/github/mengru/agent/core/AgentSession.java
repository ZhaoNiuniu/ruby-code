package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentMemory;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ConversationMessage;
import io.github.mengru.agent.core.context.ContextManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentSession {

    public static final int DEFAULT_HISTORY_TURNS = 10;
    public static final int DEFAULT_RECENT_TURNS_AFTER_COMPRESSION = 3;

    private final Agent agent;
    private final int maxHistoryTurns;
    private final int recentTurnsAfterCompression;
    private final List<ConversationMessage> conversationHistory = new ArrayList<>();
    private AgentMemory memory = AgentMemory.empty();

    public AgentSession(Agent agent) {
        this(agent, DEFAULT_HISTORY_TURNS, DEFAULT_RECENT_TURNS_AFTER_COMPRESSION);
    }

    public AgentSession(Agent agent, int maxHistoryTurns) {
        this(agent, maxHistoryTurns, Math.min(DEFAULT_RECENT_TURNS_AFTER_COMPRESSION, maxHistoryTurns));
    }

    public AgentSession(Agent agent, int maxHistoryTurns, int recentTurnsAfterCompression) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("maxHistoryTurns must be greater than zero");
        }
        if (recentTurnsAfterCompression < 0) {
            throw new IllegalArgumentException("recentTurnsAfterCompression must not be negative");
        }
        if (recentTurnsAfterCompression > maxHistoryTurns) {
            throw new IllegalArgumentException("recentTurnsAfterCompression must not exceed maxHistoryTurns");
        }
        this.maxHistoryTurns = maxHistoryTurns;
        this.recentTurnsAfterCompression = recentTurnsAfterCompression;
    }

    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRequest requestWithHistory = new AgentRequest(
                request.task(),
                request.maxSteps(),
                request.metadata(),
                request.systemPrompt(),
                conversationHistory,
                mergeMemory(request.memory())
        );
        AgentResult result = agent.run(requestWithHistory);
        if (result.completed()) {
            conversationHistory.add(ConversationMessage.user(request.task()));
            conversationHistory.add(ConversationMessage.assistant(result.output()));
            List<ContextCompressionEvent> sessionEvents = compactHistoryIfNeeded();
            if (!sessionEvents.isEmpty()) {
                List<ContextCompressionEvent> allEvents = new ArrayList<>(result.compressionEvents());
                allEvents.addAll(sessionEvents);
                return new AgentResult(result.output(), result.steps(), result.completed(), allEvents);
            }
        }
        return result;
    }

    public List<ConversationMessage> conversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public AgentMemory memory() {
        return memory;
    }

    public void clear() {
        conversationHistory.clear();
        memory = AgentMemory.empty();
    }

    private AgentMemory mergeMemory(AgentMemory requestMemory) {
        if (memory.isEmpty()) {
            return requestMemory;
        }
        if (requestMemory == null || requestMemory.isEmpty()) {
            return memory;
        }
        return memory.appendSection("Request Memory", requestMemory.markdown());
    }

    private List<ContextCompressionEvent> compactHistoryIfNeeded() {
        int maxMessages = maxHistoryTurns * 2;
        if (conversationHistory.size() <= maxMessages) {
            return List.of();
        }

        int recentMessages = recentTurnsAfterCompression * 2;
        int compactUntil = Math.max(0, conversationHistory.size() - recentMessages);
        if (compactUntil == 0) {
            return List.of();
        }

        List<ConversationMessage> oldMessages = new ArrayList<>(conversationHistory.subList(0, compactUntil));
        int beforeTokens = ContextManager.estimateTokens(renderMessages(conversationHistory));
        memory = memory.appendSection("Session Memory", renderMessages(oldMessages));
        conversationHistory.subList(0, compactUntil).clear();
        int afterTokens = ContextManager.estimateTokens(memory.markdown() + renderMessages(conversationHistory));
        return List.of(new ContextCompressionEvent(
                ContextCompressionEvent.Stage.SESSION_MEMORY_COMPACT,
                "conversation history exceeded " + maxHistoryTurns + " turns",
                beforeTokens,
                afterTokens,
                "old successful turns moved into AgentMemory; kept recent "
                        + recentTurnsAfterCompression + " turns"
        ));
    }

    private String renderMessages(List<ConversationMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i += 2) {
            ConversationMessage user = messages.get(i);
            ConversationMessage assistant = i + 1 < messages.size() ? messages.get(i + 1) : null;
            builder.append("- User: ").append(user.content()).append('\n');
            if (assistant != null) {
                builder.append("  Assistant: ").append(assistant.content()).append('\n');
            }
        }
        return builder.toString().strip();
    }
}
