package io.github.mengru.agent.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AgentRequest(
        String task,
        int maxSteps,
        Map<String, String> metadata,
        String systemPrompt,
        List<ConversationMessage> conversationHistory
) {

    private static final int DEFAULT_MAX_STEPS = 8;
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a concise, capable agent. Use available tools when they help, then provide a final answer.
            """.strip();

    public AgentRequest(String task, int maxSteps, Map<String, String> metadata) {
        this(task, maxSteps, metadata, DEFAULT_SYSTEM_PROMPT, List.of());
    }

    public AgentRequest(String task, int maxSteps, Map<String, String> metadata, String systemPrompt) {
        this(task, maxSteps, metadata, systemPrompt, List.of());
    }

    public AgentRequest {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(conversationHistory, "conversationHistory must not be null");
        systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
        if (task.isBlank()) {
            throw new IllegalArgumentException("task must not be blank");
        }
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be greater than zero");
        }
        metadata = Map.copyOf(metadata);
        conversationHistory = List.copyOf(conversationHistory);
    }

    public static AgentRequest of(String task) {
        return new AgentRequest(task, DEFAULT_MAX_STEPS, Map.of());
    }

    public static AgentRequest of(String task, String systemPrompt) {
        return new AgentRequest(task, DEFAULT_MAX_STEPS, Map.of(), systemPrompt);
    }
}
