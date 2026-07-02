package io.github.mengru.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AgentStep(
        Type type,
        String content,
        String toolCallId,
        String toolName,
        JsonNode toolArguments,
        Map<String, String> metadata
) {

    public enum Type {
        THOUGHT,
        TOOL_CALL,
        TOOL_RESULT,
        TASK_NOTIFICATION,
        FINAL_ANSWER,
        ERROR
    }

    public AgentStep {
        Objects.requireNonNull(type, "type must not be null");
        content = content == null ? "" : content;
        if (toolArguments == null) {
            toolArguments = JsonNodeFactory.instance.objectNode();
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentStep(Type type, String content, String toolCallId, String toolName, JsonNode toolArguments) {
        this(type, content, toolCallId, toolName, toolArguments, Map.of());
    }

    public static AgentStep thought(String content) {
        return new AgentStep(Type.THOUGHT, content, null, null, null);
    }

    public static AgentStep toolCall(String toolName, String toolInput) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("input", toolInput == null ? "" : toolInput);
        return toolCall(null, toolName, arguments);
    }

    public static AgentStep toolCall(String toolCallId, String toolName, JsonNode toolArguments) {
        return toolCall(toolCallId, toolName, toolArguments, Map.of());
    }

    public static AgentStep toolCall(String toolCallId, String toolName, JsonNode toolArguments, Map<String, String> metadata) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        return new AgentStep(Type.TOOL_CALL, "Call tool: " + toolName, toolCallId, toolName, toolArguments, metadata);
    }

    public static AgentStep toolResult(String toolName, String output) {
        return toolResult(null, toolName, output);
    }

    public static AgentStep toolResult(String toolCallId, String toolName, String output) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        return new AgentStep(Type.TOOL_RESULT, output, toolCallId, toolName, null);
    }

    public static AgentStep taskNotification(BackgroundTaskNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        return new AgentStep(Type.TASK_NOTIFICATION, notification.asMessage(), null, null, null);
    }

    public static AgentStep finalAnswer(String content) {
        return new AgentStep(Type.FINAL_ANSWER, content, null, null, null);
    }

    public static AgentStep finalAnswer(String content, Map<String, String> metadata) {
        return new AgentStep(Type.FINAL_ANSWER, content, null, null, null, metadata);
    }

    public static AgentStep error(String content) {
        return new AgentStep(Type.ERROR, content, null, null, null);
    }

    public Optional<String> toolCallIdOptional() {
        return Optional.ofNullable(toolCallId);
    }

    public Optional<String> toolNameOptional() {
        return Optional.ofNullable(toolName);
    }

    public JsonNode toolArgumentsOrEmptyObject() {
        return toolArguments == null ? JsonNodeFactory.instance.objectNode() : toolArguments;
    }

    public AgentStep withMetadata(Map<String, String> newMetadata) {
        return new AgentStep(type, content, toolCallId, toolName, toolArguments, newMetadata);
    }
}
