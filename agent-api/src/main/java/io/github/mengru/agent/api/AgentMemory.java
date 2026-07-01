package io.github.mengru.agent.api;

import java.util.Objects;

public record AgentMemory(String markdown) {

    private static final AgentMemory EMPTY = new AgentMemory("");

    public AgentMemory {
        markdown = markdown == null ? "" : markdown.strip();
    }

    public static AgentMemory empty() {
        return EMPTY;
    }

    public static AgentMemory of(String markdown) {
        return new AgentMemory(markdown);
    }

    public boolean isEmpty() {
        return markdown.isBlank();
    }

    public AgentMemory appendSection(String title, String content) {
        Objects.requireNonNull(title, "title must not be null");
        String sectionContent = content == null ? "" : content.strip();
        if (sectionContent.isBlank()) {
            return this;
        }
        String section = "## " + title.strip() + "\n\n" + sectionContent;
        if (isEmpty()) {
            return new AgentMemory(section);
        }
        return new AgentMemory(markdown + "\n\n" + section);
    }
}
