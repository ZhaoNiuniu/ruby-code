package io.github.mengru.agent.api;

import java.util.Objects;

public record ToolResult(boolean success, String output, String error) {

    public ToolResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output, "");
    }

    public static ToolResult failure(String error) {
        Objects.requireNonNull(error, "error must not be null");
        return new ToolResult(false, "", error);
    }
}
