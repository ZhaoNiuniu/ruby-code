package io.github.mengru.agent.api;

public record ModelOptions(int maxOutputTokens) {

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    public ModelOptions {
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be greater than zero");
        }
    }

    public static ModelOptions defaults() {
        return new ModelOptions(DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public ModelOptions withMaxOutputTokens(int newMaxOutputTokens) {
        return new ModelOptions(newMaxOutputTokens);
    }
}
