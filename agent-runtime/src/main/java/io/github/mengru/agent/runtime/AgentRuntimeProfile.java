package io.github.mengru.agent.runtime;

public record AgentRuntimeProfile(
        String provider,
        String model,
        String baseUrl,
        String system,
        ToolSettings tools,
        MemorySettings memory,
        ContextCompressionSettings contextCompression,
        ErrorRecoverySettings errorRecovery,
        TraceSettings trace,
        PolicySettings policy
) {

    public AgentRuntimeProfile {
        tools = tools == null ? ToolSettings.empty() : tools;
        memory = memory == null ? MemorySettings.empty() : memory;
        contextCompression = contextCompression == null ? ContextCompressionSettings.empty() : contextCompression;
        errorRecovery = errorRecovery == null ? ErrorRecoverySettings.empty() : errorRecovery;
        trace = trace == null ? TraceSettings.empty() : trace;
        policy = policy == null ? PolicySettings.empty() : policy;
    }

    public static AgentRuntimeProfile empty() {
        return new AgentRuntimeProfile(null, null, null, null, null, null, null, null, null, null);
    }

    public AgentRuntimeProfile merge(AgentRuntimeProfile override) {
        if (override == null) {
            return this;
        }
        return new AgentRuntimeProfile(
                override.provider == null ? provider : override.provider,
                override.model == null ? model : override.model,
                override.baseUrl == null ? baseUrl : override.baseUrl,
                override.system == null ? system : override.system,
                tools.merge(override.tools),
                memory.merge(override.memory),
                contextCompression.merge(override.contextCompression),
                errorRecovery.merge(override.errorRecovery),
                trace.merge(override.trace),
                policy.merge(override.policy)
        );
    }
}
