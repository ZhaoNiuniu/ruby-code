package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.Objects;

public record PromptAssemblyContext(
        PromptMode mode,
        AgentRequest request,
        ToolRegistry toolRegistry,
        MemoryCatalog memoryCatalog,
        SkillCatalog skillCatalog,
        Path workspace,
        String userInstructions,
        String originalTask,
        String subagentExpectedOutput
) {

    public PromptAssemblyContext {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        request = Objects.requireNonNull(request, "request must not be null");
        toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        memoryCatalog = Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        workspace = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
        userInstructions = userInstructions == null ? "" : userInstructions.strip();
        originalTask = originalTask == null ? request.task() : originalTask.strip();
        if (originalTask.isBlank()) {
            originalTask = request.task();
        }
        subagentExpectedOutput = subagentExpectedOutput == null ? "" : subagentExpectedOutput.strip();
    }
}
