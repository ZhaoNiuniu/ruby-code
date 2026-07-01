package io.github.mengru.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Optional;

public record AgentStep(Type type, String content, String toolCallId, String toolName, JsonNode toolArguments) {

    public enum Type {
        THOUGHT,
        TOOL_CALL,
        TOOL_RESULT,
        FINAL_ANSWER,
        ERROR
    }

    public AgentStep {
        Objects.requireNonNull(type, "type must not be null");
        content = content == null ? "" : content;
        if (toolArguments == null) {
            toolArguments = JsonNodeFactory.instance.objectNode();
        }
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
        Objects.requireNonNull(toolName, "toolName must not be null");
        return new AgentStep(Type.TOOL_CALL, "Call tool: " + toolName, toolCallId, toolName, toolArguments);
    }

    public static AgentStep toolResult(String toolName, String output) {
        return toolResult(null, toolName, output);
    }

    public static AgentStep toolResult(String toolCallId, String toolName, String output) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        return new AgentStep(Type.TOOL_RESULT, output, toolCallId, toolName, null);
    }

    public static AgentStep finalAnswer(String content) {
        return new AgentStep(Type.FINAL_ANSWER, content, null, null, null);
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
}
