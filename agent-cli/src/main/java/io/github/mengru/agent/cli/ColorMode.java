package io.github.mengru.agent.cli;

import java.util.Locale;

enum ColorMode {
    AUTO,
    ALWAYS,
    NEVER;

    static ColorMode parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "always" -> ALWAYS;
            case "never" -> NEVER;
            default -> throw new IllegalArgumentException("--color must be one of: auto, always, never.");
        };
    }
}
