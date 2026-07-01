package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.core.hook.AgentHook;
import io.github.mengru.agent.core.hook.HookResult;
import io.github.mengru.agent.core.hook.UserPromptSubmitContext;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.Objects;

public final class PromptAssemblyHook implements AgentHook<UserPromptSubmitContext> {

    private final PromptMode mode;
    private final ToolRegistry toolRegistry;
    private final MemoryCatalog memoryCatalog;
    private final SkillCatalog skillCatalog;
    private final PromptAssembler assembler;

    public PromptAssemblyHook(
            PromptMode mode,
            ToolRegistry toolRegistry,
            MemoryCatalog memoryCatalog,
            SkillCatalog skillCatalog
    ) {
        this(mode, toolRegistry, memoryCatalog, skillCatalog, new PromptAssembler());
    }

    public PromptAssemblyHook(
            PromptMode mode,
            ToolRegistry toolRegistry,
            MemoryCatalog memoryCatalog,
            SkillCatalog skillCatalog,
            PromptAssembler assembler
    ) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.memoryCatalog = Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        this.assembler = Objects.requireNonNull(assembler, "assembler must not be null");
    }

    @Override
    public HookResult<UserPromptSubmitContext> apply(UserPromptSubmitContext context) {
        AgentRequest request = context.request();
        String userInstructions = userInstructions(request);
        String originalTask = originalTask(request);
        String expectedOutput = request.metadata()
                .getOrDefault(PromptAssembler.SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY, "");
        Path workspace = memoryCatalog.workspace();
        AgentRequest assembled = assembler.assemble(new PromptAssemblyContext(
                mode,
                request,
                toolRegistry,
                memoryCatalog,
                skillCatalog,
                workspace,
                userInstructions,
                originalTask,
                expectedOutput
        ));
        return HookResult.replace(new UserPromptSubmitContext(assembled));
    }

    public PromptAssembler assembler() {
        return assembler;
    }

    private static String userInstructions(AgentRequest request) {
        if ("true".equals(request.metadata().get(PromptAssembler.ASSEMBLED_METADATA_KEY))) {
            return request.metadata().getOrDefault(PromptAssembler.USER_INSTRUCTIONS_METADATA_KEY, "");
        }
        return request.systemPrompt();
    }

    private static String originalTask(AgentRequest request) {
        if ("true".equals(request.metadata().get(PromptAssembler.ASSEMBLED_METADATA_KEY))) {
            return request.metadata().getOrDefault(PromptAssembler.ORIGINAL_TASK_METADATA_KEY, request.task());
        }
        return request.task();
    }
}
