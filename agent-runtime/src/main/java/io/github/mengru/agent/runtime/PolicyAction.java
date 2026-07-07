package io.github.mengru.agent.runtime;

import java.util.Locale;

public enum PolicyAction {
    ALLOW("allow", 0),
    ASK_USER("ask", 1),
    DENY("deny", 2);

    private final String value;
    private final int strictness;

    PolicyAction(String value, int strictness) {
        this.value = value;
        this.strictness = strictness;
    }

    public String value() {
        return value;
    }

    public boolean stricterThan(PolicyAction other) {
        return strictness > other.strictness;
    }

    public static PolicyAction stricter(PolicyAction left, PolicyAction right) {
        return left.stricterThan(right) ? left : right;
    }

    public static PolicyAction parse(String value) {
        if (value == null || value.isBlank()) {
            throw new RuntimeProfileException("Policy action must not be blank");
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "ask", "ask_user" -> ASK_USER;
            case "deny" -> DENY;
            default -> throw new RuntimeProfileException("Unknown policy action: " + value);
        };
    }
}
