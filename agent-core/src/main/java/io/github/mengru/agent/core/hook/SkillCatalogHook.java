package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.skill.SkillDefinition;

import java.util.Objects;

public final class SkillCatalogHook implements AgentHook<UserPromptSubmitContext> {

    static final String MARKER = "[skill catalog]";

    private final SkillCatalog skillCatalog;

    public SkillCatalogHook(SkillCatalog skillCatalog) {
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
    }

    @Override
    public HookResult<UserPromptSubmitContext> apply(UserPromptSubmitContext context) {
        if (skillCatalog.isEmpty()) {
            return HookResult.continueWith(context);
        }

        AgentRequest request = context.request();
        if (request.systemPrompt().contains(MARKER)) {
            return HookResult.continueWith(context);
        }

        String block = renderBlock();
        String systemPrompt = request.systemPrompt().isBlank()
                ? block
                : request.systemPrompt() + "\n\n" + block;
        return HookResult.replace(new UserPromptSubmitContext(new AgentRequest(
                request.task(),
                request.maxSteps(),
                request.metadata(),
                systemPrompt,
                request.conversationHistory()
        )));
    }

    private String renderBlock() {
        StringBuilder builder = new StringBuilder(MARKER)
                .append('\n')
                .append("Project skills are available on demand. Call load_skill with one of these names before relying on a skill's full instructions:")
                .append('\n');
        for (SkillDefinition skill : skillCatalog.skills()) {
            builder.append("- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description())
                    .append('\n');
        }
        return builder.toString().strip();
    }
}
