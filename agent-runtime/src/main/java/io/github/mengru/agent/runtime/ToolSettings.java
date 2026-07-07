package io.github.mengru.agent.runtime;

public record ToolSettings(
        Boolean local,
        Boolean subagent,
        Boolean team,
        Boolean scheduler,
        Boolean mcp,
        String mcpConfig
) {

    public static ToolSettings empty() {
        return new ToolSettings(null, null, null, null, null, null);
    }

    public ToolSettings merge(ToolSettings override) {
        if (override == null) {
            return this;
        }
        return new ToolSettings(
                choose(local, override.local),
                choose(subagent, override.subagent),
                choose(team, override.team),
                choose(scheduler, override.scheduler),
                choose(mcp, override.mcp),
                choose(mcpConfig, override.mcpConfig)
        );
    }

    private static <T> T choose(T base, T override) {
        return override == null ? base : override;
    }
}
