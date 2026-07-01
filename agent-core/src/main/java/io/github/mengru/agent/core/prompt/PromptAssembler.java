package io.github.mengru.agent.core.prompt;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.core.memory.MemoryDefinition;
import io.github.mengru.agent.core.memory.MemoryRetriever;
import io.github.mengru.agent.core.tool.ToolOutputSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PromptAssembler {

    public static final String ASSEMBLED_METADATA_KEY = "agent.promptAssembled";
    public static final String ORIGINAL_TASK_METADATA_KEY = "agent.originalTask";
    public static final String USER_INSTRUCTIONS_METADATA_KEY = "agent.userInstructions";
    public static final String SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY = "agent.subagentExpectedOutput";

    private static final String RELEVANT_MEMORY_MARKER = "## Relevant Long-Term Memory";
    private static final int MAX_RELEVANT_MEMORY_CHARS = 12_000;

    private final List<PromptSection> sections;
    private final Map<String, String> systemCache = new LinkedHashMap<>();
    private int cacheHits;

    public PromptAssembler() {
        this(PromptSections.defaults());
    }

    public PromptAssembler(List<PromptSection> sections) {
        this.sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    }

    public AgentRequest assemble(PromptAssemblyContext context) {
        Objects.requireNonNull(context, "context must not be null");
        String cacheKey = cacheKey(context);
        String systemPrompt = systemCache.get(cacheKey);
        if (systemPrompt == null) {
            systemPrompt = renderSystemPrompt(context);
            systemCache.put(cacheKey, systemPrompt);
        } else {
            cacheHits++;
        }

        String task = renderTask(context);
        Map<String, String> metadata = assembledMetadata(context);
        return new AgentRequest(
                task,
                context.request().maxSteps(),
                metadata,
                systemPrompt,
                context.request().conversationHistory(),
                context.request().memory()
        );
    }

    public int cacheSize() {
        return systemCache.size();
    }

    public int cacheHits() {
        return cacheHits;
    }

    private String renderSystemPrompt(PromptAssemblyContext context) {
        StringBuilder builder = new StringBuilder();
        for (PromptSection section : sections) {
            if (!section.shouldRender(context)) {
                continue;
            }
            String rendered = section.render(context).strip();
            if (rendered.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(rendered);
        }
        return builder.toString();
    }

    private String renderTask(PromptAssemblyContext context) {
        List<MemoryDefinition> memories = new MemoryRetriever(context.memoryCatalog()).retrieve(retrievalRequest(context));
        if (memories.isEmpty()) {
            return context.originalTask();
        }

        StringBuilder injected = new StringBuilder(context.originalTask())
                .append("\n\n")
                .append(RELEVANT_MEMORY_MARKER)
                .append("\n\nThese memories were selected deterministically from the local project memory store. Treat them as background context.\n");
        int used = 0;
        for (MemoryDefinition memory : memories) {
            String content = ToolOutputSupport.truncate(memory.content());
            int remaining = MAX_RELEVANT_MEMORY_CHARS - used;
            if (remaining <= 0) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining) + "\n[memory content truncated for injection budget]";
            }
            used += content.length();
            injected.append("\n### ")
                    .append(memory.name())
                    .append(" (")
                    .append(memory.type().value())
                    .append(")\n")
                    .append(content)
                    .append('\n');
        }
        return injected.toString().strip();
    }

    private AgentRequest retrievalRequest(PromptAssemblyContext context) {
        return new AgentRequest(
                context.originalTask(),
                context.request().maxSteps(),
                context.request().metadata(),
                context.userInstructions(),
                context.request().conversationHistory(),
                context.request().memory()
        );
    }

    private Map<String, String> assembledMetadata(PromptAssemblyContext context) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(context.request().metadata());
        metadata.put(ASSEMBLED_METADATA_KEY, "true");
        metadata.put(ORIGINAL_TASK_METADATA_KEY, context.originalTask());
        metadata.put(USER_INSTRUCTIONS_METADATA_KEY, context.userInstructions());
        if (!context.subagentExpectedOutput().isBlank()) {
            metadata.put(SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY, context.subagentExpectedOutput());
        } else {
            metadata.remove(SUBAGENT_EXPECTED_OUTPUT_METADATA_KEY);
        }
        return metadata;
    }

    private String cacheKey(PromptAssemblyContext context) {
        StringBuilder builder = new StringBuilder()
                .append("mode=").append(context.mode()).append('\n')
                .append("userInstructions=").append(context.userInstructions()).append('\n')
                .append("workspace=").append(context.workspace()).append('\n')
                .append("memory=").append(context.request().memory().markdown()).append('\n')
                .append("memoryIndex=").append(context.memoryCatalog().renderIndex()).append('\n')
                .append("skills=");
        context.skillCatalog().skills().forEach(skill -> builder
                .append(skill.name()).append(':').append(skill.description()).append('\n'));
        builder.append("tools=");
        context.toolRegistry().tools().forEach(tool -> builder
                .append(tool.name()).append(':').append(tool.description()).append('\n'));
        builder.append("expected=").append(context.subagentExpectedOutput());
        return builder.toString();
    }
}
