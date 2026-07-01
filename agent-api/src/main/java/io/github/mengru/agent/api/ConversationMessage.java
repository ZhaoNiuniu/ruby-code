package io.github.mengru.agent.api;

import java.util.Objects;

public record ConversationMessage(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public ConversationMessage {
        Objects.requireNonNull(role, "role must not be null");
        content = content == null ? "" : content;
    }

    public static ConversationMessage user(String content) {
        return new ConversationMessage(Role.USER, content);
    }

    public static ConversationMessage assistant(String content) {
        return new ConversationMessage(Role.ASSISTANT, content);
    }
}
