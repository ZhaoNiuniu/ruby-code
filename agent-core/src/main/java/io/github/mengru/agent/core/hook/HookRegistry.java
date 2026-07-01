package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.memory.MemoryExtractor;
import io.github.mengru.agent.core.memory.MemoryStore;
import io.github.mengru.agent.core.permission.DefaultPermissionChecker;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.prompt.PromptAssemblyHook;
import io.github.mengru.agent.core.prompt.PromptMode;
import io.github.mengru.agent.core.skill.LoadSkillTool;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HookRegistry {

    private final Map<HookEvent, List<AgentHook<?>>> hooks = new EnumMap<>(HookEvent.class);

    public HookRegistry() {
        for (HookEvent event : HookEvent.values()) {
            hooks.put(event, new ArrayList<>());
        }
    }

    public static HookRegistry empty() {
        return new HookRegistry();
    }

    public static HookRegistry defaults() {
        return defaults(UserApprover.denyAll());
    }

    public static HookRegistry defaults(UserApprover userApprover) {
        return empty()
                .registerDefaultPermissionHook(userApprover);
    }

    public static HookRegistry defaultsFor(ToolRegistry toolRegistry) {
        return defaultsFor(toolRegistry, UserApprover.denyAll());
    }

    public static HookRegistry defaultsFor(ToolRegistry toolRegistry, UserApprover userApprover) {
        return defaultsFor(toolRegistry, userApprover, MemoryCatalog.empty(java.nio.file.Path.of("")));
    }

    public static HookRegistry defaultsFor(ToolRegistry toolRegistry, UserApprover userApprover, MemoryCatalog memoryCatalog) {
        return defaultsFor(toolRegistry, userApprover, memoryCatalog, null, null);
    }

    public static HookRegistry defaultsFor(
            ToolRegistry toolRegistry,
            UserApprover userApprover,
            MemoryCatalog memoryCatalog,
            PromptMode promptMode
    ) {
        return defaultsFor(toolRegistry, userApprover, memoryCatalog, null, null, promptMode);
    }

    public static HookRegistry defaultsFor(
            ToolRegistry toolRegistry,
            UserApprover userApprover,
            MemoryCatalog memoryCatalog,
            MemoryExtractor memoryExtractor,
            MemoryStore memoryStore
    ) {
        return defaultsFor(toolRegistry, userApprover, memoryCatalog, memoryExtractor, memoryStore, PromptMode.MAIN);
    }

    public static HookRegistry defaultsFor(
            ToolRegistry toolRegistry,
            UserApprover userApprover,
            MemoryCatalog memoryCatalog,
            MemoryExtractor memoryExtractor,
            MemoryStore memoryStore,
            PromptMode promptMode
    ) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        HookRegistry registry = empty();
        SkillCatalog skillCatalog = skillCatalogFrom(toolRegistry);
        registry.registerHook(HookEvent.USER_PROMPT_SUBMIT, new PromptAssemblyHook(
                Objects.requireNonNull(promptMode, "promptMode must not be null"),
                toolRegistry,
                memoryCatalog,
                skillCatalog
        ));
        if (memoryExtractor != null && memoryStore != null) {
            registry.registerHook(HookEvent.STOP, new MemoryExtractionHook(memoryExtractor, memoryStore));
        }
        return registry.registerDefaultPermissionHook(userApprover);
    }

    private static SkillCatalog skillCatalogFrom(ToolRegistry toolRegistry) {
        return toolRegistry.findByName(LoadSkillTool.NAME)
                .filter(LoadSkillTool.class::isInstance)
                .map(LoadSkillTool.class::cast)
                .map(LoadSkillTool::skillCatalog)
                .orElseGet(SkillCatalog::empty);
    }

    public <T> HookRegistry registerHook(HookEvent event, AgentHook<T> hook) {
        hooks.get(Objects.requireNonNull(event, "event must not be null"))
                .add(Objects.requireNonNull(hook, "hook must not be null"));
        return this;
    }

    private HookRegistry registerDefaultPermissionHook(UserApprover userApprover) {
        return registerHook(
                HookEvent.PRE_TOOL_USE,
                new PreToolUsePermissionHook(new DefaultPermissionChecker(), Objects.requireNonNull(userApprover, "userApprover must not be null"))
        );
    }

    @SuppressWarnings("unchecked")
    public <T> HookResult<T> triggerHooks(HookEvent event, T context) {
        Objects.requireNonNull(event, "event must not be null");
        T current = Objects.requireNonNull(context, "context must not be null");
        for (AgentHook<?> hook : hooks.get(event)) {
            HookResult<T> result = ((AgentHook<T>) hook).apply(current);
            if (result == null) {
                continue;
            }
            if (result.outcome() == HookResult.Outcome.BLOCK) {
                return HookResult.block(result.reason(), result.context() == null ? current : result.context());
            }
            if (result.outcome() == HookResult.Outcome.REPLACE) {
                current = Objects.requireNonNull(result.context(), "replacement context must not be null");
            }
        }
        return HookResult.continueWith(current);
    }
}
