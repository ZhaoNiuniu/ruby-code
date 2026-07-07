package io.github.mengru.agent.runtime;

public record PolicySettings(String name, String mode) {

    public static PolicySettings empty() {
        return new PolicySettings(null, null);
    }

    public PolicySettings merge(PolicySettings override) {
        if (override == null) {
            return this;
        }
        String mergedName = override.name == null ? name : override.name;
        String mergedMode = override.mode == null ? mode : override.mode;
        return new PolicySettings(mergedName, mergedMode);
    }

    public String effectiveName() {
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        if (mode != null && !mode.isBlank()) {
            return mode.strip();
        }
        return BuiltInPolicies.DEV;
    }
}
