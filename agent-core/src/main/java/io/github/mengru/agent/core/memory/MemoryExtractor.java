package io.github.mengru.agent.core.memory;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MemoryExtractor {

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            Extract one durable memory from the completed agent turn.
            Return a Markdown document with frontmatter:
            ---
            name: stable-slug
            description: short description
            type: user|feedback|project|reference
            ---
            
            Memory content.
            
            Only extract information useful across future sessions. If no memory should be saved, return exactly NO_MEMORY.
            """.strip();

    private final ModelClient modelClient;

    public MemoryExtractor(ModelClient modelClient) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
    }

    public Optional<MemoryCandidate> extract(AgentRequest request, AgentResult result) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!shouldExtract(request, result)) {
            return Optional.empty();
        }
        AgentRequest extractionRequest = new AgentRequest(
                extractionTask(request, result),
                1,
                Map.of("agent.memoryExtraction", "true"),
                EXTRACTION_SYSTEM_PROMPT,
                request.conversationHistory(),
                request.memory()
        );
        AgentStep step = modelClient.nextStep(extractionRequest, result.steps(), java.util.List.of());
        return parseCandidate(step.content());
    }

    boolean shouldExtract(AgentRequest request, AgentResult result) {
        if (!result.completed()) {
            return false;
        }
        String text = (request.task() + "\n" + result.output()).toLowerCase(Locale.ROOT);
        return text.contains("记住")
                || text.contains("记下来")
                || text.contains("以后")
                || text.contains("偏好")
                || text.contains("不要")
                || text.contains("别 ")
                || text.contains("remember")
                || text.contains("preference")
                || text.contains("always ")
                || text.contains("never ");
    }

    Optional<MemoryCandidate> parseCandidate(String text) {
        if (text == null || text.isBlank() || "NO_MEMORY".equals(text.strip())) {
            return Optional.empty();
        }
        String content = text.strip();
        if (!content.startsWith("---\n")) {
            return Optional.empty();
        }
        int end = content.indexOf("\n---", 4);
        if (end < 0) {
            return Optional.empty();
        }
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String rawLine : content.substring(4, end).split("\n")) {
            int colon = rawLine.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            fields.put(rawLine.substring(0, colon).strip(), unquote(rawLine.substring(colon + 1).strip()));
        }
        Optional<MemoryType> type = MemoryType.from(fields.get("type"));
        if (fields.get("name") == null
                || fields.get("description") == null
                || type.isEmpty()) {
            return Optional.empty();
        }
        String body = content.substring(end + "\n---".length()).strip();
        if (body.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MemoryCandidate(fields.get("name"), fields.get("description"), type.get(), body));
    }

    private String extractionTask(AgentRequest request, AgentResult result) {
        return """
                Current user task:
                %s
                
                Assistant final answer:
                %s
                
                Decide whether to save one long-term memory.
                """.formatted(request.task(), result.output()).strip();
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
