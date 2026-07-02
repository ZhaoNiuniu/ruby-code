package io.github.mengru.agent.core.team;

import java.util.Locale;

public enum TeamMessageType {
    PLAIN_TEXT("plain_text"),
    TASK_ASSIGNMENT("task_assignment"),
    STATUS_UPDATE("status_update"),
    PERMISSION_REQUEST("permission_request"),
    PERMISSION_RESPONSE("permission_response"),
    SHUTDOWN_REQUEST("shutdown_request"),
    SHUTDOWN_ACK("shutdown_ack"),
    TEAMMATE_TERMINATED("teammate_terminated");

    private final String value;

    TeamMessageType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TeamMessageType from(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (TeamMessageType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported team message type: " + value);
    }
}
