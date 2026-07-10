package io.github.mengru.agent.runtime;

import io.github.mengru.agent.core.context.ContextCompressionConfig;
import io.github.mengru.agent.core.recovery.ErrorRecoveryConfig;
final class BuiltInRuntimeProfiles {

    static final String DEV = "dev";
    static final String READONLY = "readonly";

    private BuiltInRuntimeProfiles() {
    }

    static boolean isBuiltIn(String name) {
        return DEV.equals(name) || READONLY.equals(name);
    }

    static AgentRuntimeProfile defaults() {
        return new AgentRuntimeProfile(
                "echo",
                null,
                null,
                null,
                new ToolSettings(true, true, true, true, true, ".mcp.json"),
                new MemorySettings(true, true),
                new ContextCompressionSettings(
                        true,
                        128000,
                        4000,
                        13000
                ),
                new ErrorRecoverySettings(
                        true,
                        3,
                        8192,
                        65536
                ),
                new TraceSettings(false, "file"),
                new PolicySettings(BuiltInPolicies.DEV, null)
        );
    }

    static AgentRuntimeProfile profile(String name) {
        return switch (name) {
            case DEV -> AgentRuntimeProfile.empty();
            case READONLY -> readonly();
            default -> throw new RuntimeProfileException("Unknown built-in runtime profile: " + name);
        };
    }

    private static AgentRuntimeProfile readonly() {
        return new AgentRuntimeProfile(
                null,
                null,
                null,
                null,
                new ToolSettings(true, false, true, true, false, ".mcp.json"),
                null,
                null,
                null,
                null,
                new PolicySettings(BuiltInPolicies.READONLY, null)
        );
    }
}
