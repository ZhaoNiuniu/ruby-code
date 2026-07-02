package io.github.mengru.agent.core;

import io.github.mengru.agent.api.Agent;
import io.github.mengru.agent.api.AgentMemory;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.BackgroundTaskNotification;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ConversationMessage;
import io.github.mengru.agent.api.TraceEvent;
import io.github.mengru.agent.core.background.BackgroundTaskManager;
import io.github.mengru.agent.core.context.ContextManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentSession {

    public static final int DEFAULT_HISTORY_TURNS = 10;
    public static final int DEFAULT_RECENT_TURNS_AFTER_COMPRESSION = 3;

    private final Agent agent;
    private final BackgroundTaskManager backgroundTaskManager;
    private final int maxHistoryTurns;
    private final int recentTurnsAfterCompression;
    private final List<ConversationMessage> conversationHistory = new ArrayList<>();
    private AgentMemory memory = AgentMemory.empty();

    public AgentSession(Agent agent) {
        this(agent, BackgroundTaskManager.disabled());
    }

    public AgentSession(Agent agent, BackgroundTaskManager backgroundTaskManager) {
        this(agent, backgroundTaskManager, DEFAULT_HISTORY_TURNS, DEFAULT_RECENT_TURNS_AFTER_COMPRESSION);
    }

    public AgentSession(Agent agent, int maxHistoryTurns) {
        this(agent, BackgroundTaskManager.disabled(), maxHistoryTurns, Math.min(DEFAULT_RECENT_TURNS_AFTER_COMPRESSION, maxHistoryTurns));
    }

    public AgentSession(Agent agent, int maxHistoryTurns, int recentTurnsAfterCompression) {
        this(agent, BackgroundTaskManager.disabled(), maxHistoryTurns, recentTurnsAfterCompression);
    }

    public AgentSession(Agent agent, BackgroundTaskManager backgroundTaskManager, int maxHistoryTurns, int recentTurnsAfterCompression) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.backgroundTaskManager = Objects.requireNonNull(backgroundTaskManager, "backgroundTaskManager must not be null");
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

    public synchronized AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<BackgroundTaskNotification> notifications = new ArrayList<>(request.notifications());
        notifications.addAll(backgroundTaskManager.collectCompletedNotifications());
        AgentRequest requestWithHistory = new AgentRequest(
                request.task(),
                request.maxSteps(),
                request.metadata(),
                request.systemPrompt(),
                conversationHistory,
                mergeMemory(request.memory()),
                request.modelOptions(),
                notifications
        );
        AgentResult result = agent.run(requestWithHistory);
        if (result.completed()) {
            conversationHistory.add(ConversationMessage.user(request.task()));
            conversationHistory.add(ConversationMessage.assistant(result.output()));
            List<ContextCompressionEvent> sessionEvents = compactHistoryIfNeeded();
            if (!sessionEvents.isEmpty()) {
                List<ContextCompressionEvent> allEvents = new ArrayList<>(result.compressionEvents());
                allEvents.addAll(sessionEvents);
                List<TraceEvent> traceEvents = new ArrayList<>(result.traceEvents());
                for (ContextCompressionEvent event : sessionEvents) {
                    traceEvents.add(TraceEvent.of(
                            TraceEvent.Type.COMPRESSION,
                            orderedAttributes(
                                    "stage", event.stage().name(),
                                    "reason", event.reason(),
                                    "before_tokens", Integer.toString(event.estimatedTokensBefore()),
                                    "after_tokens", Integer.toString(event.estimatedTokensAfter())
                            )
                    ));
                }
                return new AgentResult(result.output(), result.steps(), result.completed(), allEvents, result.recoveryEvents(), traceEvents);
            }
        }
        return result;
    }

    public synchronized List<ConversationMessage> conversationHistory() {
        return List.copyOf(conversationHistory);
    }

    public synchronized AgentMemory memory() {
        return memory;
    }

    public synchronized void clear() {
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

    private static Map<String, String> orderedAttributes(String... pairs) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        if (pairs == null) {
            return attributes;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return attributes;
    }
}
