package io.github.mengru.agent.cli;

import io.github.mengru.agent.api.TraceEvent;
import io.github.mengru.agent.core.trace.TraceFormatter;

final class TerminalStyle {

    static final String RESET = "\u001B[0m";
    static final String DIM = "\u001B[2m";
    static final String GREEN = "\u001B[32m";
    static final String YELLOW = "\u001B[33m";
    static final String BLUE = "\u001B[34m";
    static final String MAGENTA = "\u001B[35m";
    static final String CYAN = "\u001B[36m";
    static final String RED = "\u001B[31m";

    private final boolean enabled;

    private TerminalStyle(boolean enabled) {
        this.enabled = enabled;
    }

    static TerminalStyle of(String mode, boolean interactiveTerminal) {
        return of(ColorMode.parse(mode), interactiveTerminal);
    }

    static TerminalStyle of(ColorMode mode, boolean interactiveTerminal) {
        return new TerminalStyle(switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> interactiveTerminal;
        });
    }

    static TerminalStyle plain() {
        return new TerminalStyle(false);
    }

    boolean enabled() {
        return enabled;
    }

    String prompt(String value) {
        return color(CYAN, value);
    }

    String assistantPrefix() {
        return color(GREEN, "assistant>");
    }

    String teamPrefix(String value) {
        return color(MAGENTA, value);
    }

    String cronPrefix(String value) {
        return color(BLUE, value);
    }

    String warning(String value) {
        return color(YELLOW, value);
    }

    String error(String value) {
        return color(RED, value);
    }

    String trace(TraceEvent event) {
        String formatted = TraceFormatter.format(event);
        if (!enabled) {
            return formatted;
        }
        String line = formatted.replaceFirst("^\\[trace]", color(DIM, "[trace]"));
        return switch (event.type()) {
            case FINAL_ANSWER -> colorTraceValues(line, "completed=true", GREEN);
            case ERROR, PERMISSION_DENIED -> color(RED, line);
            case RECOVERY -> color(YELLOW, line);
            case COMPRESSION -> color(BLUE, line);
            case MODEL_CALL -> colorModelCall(line);
            case TOOL_CALL -> line;
            case TOOL_RESULT -> colorToolResult(line);
            case TASK_NOTIFICATION -> color(CYAN, line);
        };
    }

    private String colorModelCall(String line) {
        if (line.contains("status=failed")) {
            return colorTraceValues(line, "status=failed", RED);
        }
        if (line.contains("status=success")) {
            return colorTraceValues(line, "status=success", GREEN);
        }
        return line;
    }

    private String colorToolResult(String line) {
        if (line.contains("success=false")) {
            return colorTraceValues(line, "success=false", RED);
        }
        if (line.contains("success=true")) {
            return colorTraceValues(line, "success=true", GREEN);
        }
        return line;
    }

    private String colorTraceValues(String line, String needle, String ansiColor) {
        return line.replace(needle, color(ansiColor, needle));
    }

    private String color(String ansiColor, String value) {
        if (!enabled) {
            return value;
        }
        return ansiColor + value + RESET;
    }
}
