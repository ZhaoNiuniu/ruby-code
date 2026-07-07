package io.github.mengru.agent.runtime;

import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RuntimeToolFilter {

    private static final Set<String> LOCAL_TOOLS = Set.of(
            "todo_write",
            "bash",
            "read_file",
            "write_file",
            "edit_file",
            "glob"
    );
    private static final Set<String> SCHEDULER_TOOLS = Set.of(
            "schedule_task",
            "list_scheduled_tasks",
            "cancel_scheduled_task"
    );
    private static final Set<String> TEAM_TOOLS = Set.of(
            "spawn_teammate",
            "send_message",
            "list_teammates"
    );
    private RuntimeToolFilter() {
    }

    public static ToolRegistry filter(ToolRegistry registry, RuntimeSettings settings) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        List<Tool> filtered = registry.tools().stream()
                .filter(tool -> isEnabled(tool.name(), settings))
                .toList();
        return ToolRegistry.of(filtered);
    }

    public static boolean isEnabled(String toolName, RuntimeSettings settings) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        if (toolName.startsWith("mcp__")) {
            return settings.mcpTools()
                    && !isDeniedInStablePolicyContext(toolName, settings);
        }
        if (!settings.localTools() && LOCAL_TOOLS.contains(toolName)) {
            return false;
        }
        if (!settings.subagentTools() && "subagent".equals(toolName)) {
            return false;
        }
        if (!settings.schedulerTools() && SCHEDULER_TOOLS.contains(toolName)) {
            return false;
        }
        if (!settings.teamTools() && TEAM_TOOLS.contains(toolName)) {
            return false;
        }
        return !isDeniedInStablePolicyContext(toolName, settings);
    }

    private static boolean isDeniedInStablePolicyContext(String toolName, RuntimeSettings settings) {
        return settings.policyConfig()
                .actionFor(toolName, Set.of(settings.policyName()))
                == PolicyAction.DENY;
    }
}
