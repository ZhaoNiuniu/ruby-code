package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TeamMessage(
        String messageId,
        String teamId,
        TeamMessageType type,
        String from,
        String to,
        Instant createdAt,
        String content,
        String correlationId,
        JsonNode payload
) {

    public TeamMessage {
        messageId = nonBlank(messageId, "messageId");
        teamId = nonBlank(teamId, "teamId");
        type = Objects.requireNonNull(type, "type must not be null");
        from = nonBlank(from, "from");
        to = nonBlank(to, "to");
        createdAt = createdAt == null ? Instant.now() : createdAt;
        content = content == null ? "" : content.strip();
        correlationId = correlationId == null ? "" : correlationId.strip();
        payload = payload == null || payload.isNull() ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
    }

    public static TeamMessage create(
            String teamId,
            TeamMessageType type,
            String from,
            String to,
            String content,
            String correlationId,
            JsonNode payload
    ) {
        return new TeamMessage(
                "msg_" + UUID.randomUUID(),
                teamId,
                type,
                from,
                to,
                Instant.now(),
                content,
                correlationId,
                payload
        );
    }

    private static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return stripped;
    }
}
