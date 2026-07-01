package io.github.mengru.agent.core.hook;

import java.util.Objects;

public record HookResult<T>(Outcome outcome, T context, String reason) {

    public enum Outcome {
        CONTINUE,
        BLOCK,
        REPLACE
    }

    public HookResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        reason = reason == null ? "" : reason;
    }

    public static <T> HookResult<T> continueWith(T context) {
        return new HookResult<>(Outcome.CONTINUE, context, "");
    }

    public static <T> HookResult<T> replace(T context) {
        return new HookResult<>(Outcome.REPLACE, Objects.requireNonNull(context, "context must not be null"), "");
    }

    public static <T> HookResult<T> block(String reason) {
        return new HookResult<>(Outcome.BLOCK, null, reason);
    }

    public static <T> HookResult<T> block(String reason, T context) {
        return new HookResult<>(Outcome.BLOCK, context, reason);
    }
}
