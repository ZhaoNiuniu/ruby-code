package io.github.mengru.agent.api;

import java.util.Objects;

public record BackgroundTaskNotification(String bgId, String toolName, String status, String content) {

    public BackgroundTaskNotification {
        bgId = requireNonBlank(bgId, "bgId");
        toolName = requireNonBlank(toolName, "toolName");
        status = requireNonBlank(status, "status");
        content = content == null ? "" : content;
    }

    public String asMessage() {
        return "<task_notification bg_id=\"" + escapeAttribute(bgId)
                + "\" tool=\"" + escapeAttribute(toolName)
                + "\" status=\"" + escapeAttribute(status)
                + "\">\n"
                + content
                + "\n</task_notification>";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static String escapeAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
