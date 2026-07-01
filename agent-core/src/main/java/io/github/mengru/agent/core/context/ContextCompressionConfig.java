package io.github.mengru.agent.core.context;

public record ContextCompressionConfig(
        boolean enabled,
        int contextWindowTokens,
        int maxOutputTokens,
        int reservedTokens,
        int toolResultBudgetChars,
        int maxMessages,
        int recentToolResultsToKeep,
        int recentLogicalItemsAfterAutoCompact,
        int reactiveRecentLogicalItems,
        int maxAutoCompactFailures
) {

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_000;
    public static final int DEFAULT_RESERVED_TOKENS = 13_000;
    public static final int DEFAULT_TOOL_RESULT_BUDGET_CHARS = 200_000;
    public static final int DEFAULT_MAX_MESSAGES = 50;
    public static final int DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP = 3;
    public static final int DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT = 5;
    public static final int DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS = 5;
    public static final int DEFAULT_MAX_AUTO_COMPACT_FAILURES = 3;

    public ContextCompressionConfig {
        if (contextWindowTokens < 1) {
            throw new IllegalArgumentException("contextWindowTokens must be greater than zero");
        }
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
        if (reservedTokens < 0) {
            throw new IllegalArgumentException("reservedTokens must not be negative");
        }
        if (contextWindowTokens <= maxOutputTokens + reservedTokens) {
            throw new IllegalArgumentException("contextWindowTokens must be greater than maxOutputTokens + reservedTokens");
        }
        if (toolResultBudgetChars < 1) {
            throw new IllegalArgumentException("toolResultBudgetChars must be greater than zero");
        }
        if (maxMessages < 3) {
            throw new IllegalArgumentException("maxMessages must be at least 3");
        }
        if (recentToolResultsToKeep < 0) {
            throw new IllegalArgumentException("recentToolResultsToKeep must not be negative");
        }
        if (recentLogicalItemsAfterAutoCompact < 1) {
            throw new IllegalArgumentException("recentLogicalItemsAfterAutoCompact must be greater than zero");
        }
        if (reactiveRecentLogicalItems < 1) {
            throw new IllegalArgumentException("reactiveRecentLogicalItems must be greater than zero");
        }
        if (maxAutoCompactFailures < 1) {
            throw new IllegalArgumentException("maxAutoCompactFailures must be greater than zero");
        }
    }

    public static ContextCompressionConfig defaults() {
        return new ContextCompressionConfig(
                true,
                DEFAULT_CONTEXT_WINDOW_TOKENS,
                DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_RESERVED_TOKENS,
                DEFAULT_TOOL_RESULT_BUDGET_CHARS,
                DEFAULT_MAX_MESSAGES,
                DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP,
                DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT,
                DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS,
                DEFAULT_MAX_AUTO_COMPACT_FAILURES
        );
    }

    public static ContextCompressionConfig disabled() {
        return new ContextCompressionConfig(
                false,
                DEFAULT_CONTEXT_WINDOW_TOKENS,
                DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_RESERVED_TOKENS,
                DEFAULT_TOOL_RESULT_BUDGET_CHARS,
                DEFAULT_MAX_MESSAGES,
                DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP,
                DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT,
                DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS,
                DEFAULT_MAX_AUTO_COMPACT_FAILURES
        );
    }

    int modelInputBudgetTokens() {
        return contextWindowTokens - maxOutputTokens - reservedTokens;
    }
}
