package io.github.mengru.agent.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMessageTest {

    @Test
    void createsUserAndAssistantMessages() {
        ConversationMessage user = ConversationMessage.user("hello");
        ConversationMessage assistant = ConversationMessage.assistant("hi");

        assertThat(user.role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(user.content()).isEqualTo("hello");
        assertThat(assistant.role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(assistant.content()).isEqualTo("hi");
    }
}
