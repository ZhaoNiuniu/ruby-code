package io.github.mengru.agent.api;

import java.util.Objects;

public record ContextCompressionEvent(
        Stage stage,
        String reason,
        int estimatedTokensBefore,
        int estimatedTokensAfter,
        String summary
) {

    public enum Stage {
        TOOL_RESULT_BUDGET,
        SNIP_COMPACT,
        MICRO_COMPACT,
        AUTO_COMPACT,
        REACTIVE_COMPACT,
        SESSION_MEMORY_COMPACT
    }

    public ContextCompressionEvent {
        Objects.requireNonNull(stage, "stage must not be null");
        reason = reason == null ? "" : reason;
        summary = summary == null ? "" : summary;
    }
}
