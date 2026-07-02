package io.github.mengru.agent.core.tool.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.team.TeamMessage;
import io.github.mengru.agent.core.team.TeamMessageType;
import io.github.mengru.agent.core.team.TeamRuntime;

import java.util.Objects;

public final class SendMessageTool implements Tool {

    public static final String NAME = "send_message";

    private final TeamRuntime runtime;

    public SendMessageTool(TeamRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Send an explicit team message through the file inbox. Teammate routine completion is auto-reported by the runtime.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = TeamToolSupport.baseSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("to", TeamToolSupport.stringProperty("Recipient teammate name, or lead as an alias for the Lead agent."));
        ObjectNode type = TeamToolSupport.stringProperty("Message type.");
        type.putArray("enum")
                .add("plain_text")
                .add("task_assignment")
                .add("status_update")
                .add("shutdown_request")
                .add("shutdown_ack")
                .add("teammate_terminated");
        properties.set("type", type);
        properties.set("content", TeamToolSupport.stringProperty("Human-readable message content."));
        properties.set("correlationId", TeamToolSupport.stringProperty("Optional correlation id for message correlation."));
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("type", "object");
        payload.put("description", "Optional structured payload.");
        properties.set("payload", payload);
        schema.putArray("required").add("to").add("type").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            TeamMessage message = runtime.send(
                    TeamToolSupport.sender(request.metadata()),
                    request.stringArgument("to"),
                    TeamMessageType.from(request.stringArgument("type")),
                    request.stringArgument("content"),
                    request.stringArgument("correlationId"),
                    TeamToolSupport.objectArgument(request.arguments(), "payload")
            );
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.put("message", "team message sent");
            output.put("messageId", message.messageId());
            output.put("teamId", runtime.teamId());
            output.put("from", message.from());
            output.put("to", message.to());
            output.put("type", message.type().value());
            if (!message.correlationId().isBlank()) {
                output.put("correlationId", message.correlationId());
            }
            return ToolResult.success(output.toPrettyString());
        } catch (RuntimeException e) {
            return ToolResult.failure(e.getMessage());
        }
    }
}
