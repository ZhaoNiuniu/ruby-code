package io.github.mengru.agent.core.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.AgentMemory;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.PromptTooLongException;
import io.github.mengru.agent.api.Tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ContextManager {

    private static final String COMPACTION_METADATA_KEY = "agent.contextCompression";
    private static final String SUMMARY_SYSTEM_PROMPT = """
            You summarize agent execution context into durable, clean memory.
            Return concise Markdown with these sections:
            ## Goal
            ## Decisions
            ## Current State
            ## Open Items
            ## Evidence
            Keep facts, file names, commands, errors, and conclusions. Drop chatter and redundant tool output.
            """.strip();

    private final ContextCompressionConfig config;
    private int consecutiveAutoCompactFailures;
    private int autoCompactedStepCount;

    public ContextManager() {
        this(ContextCompressionConfig.defaults());
    }

    public ContextManager(ContextCompressionConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public static ContextManager defaults() {
        return new ContextManager(ContextCompressionConfig.defaults());
    }

    public static ContextManager disabled() {
        return new ContextManager(ContextCompressionConfig.disabled());
    }

    public static ContextManager withConfig(ContextCompressionConfig config) {
        return new ContextManager(config);
    }

    public ContextCompressionConfig config() {
        return config;
    }

    public ContextManager newRun() {
        return new ContextManager(config);
    }

    public ContextView prepareForModel(
            AgentRequest request,
            List<AgentStep> originalSteps,
            ModelClient modelClient,
            List<Tool> tools
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(originalSteps, "originalSteps must not be null");
        Objects.requireNonNull(modelClient, "modelClient must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        if (!config.enabled()) {
            return ContextView.ok(request, List.copyOf(originalSteps), List.of());
        }

        List<ContextCompressionEvent> events = new ArrayList<>();
        List<AgentStep> compacted = autoCompactedStepCount > 0 && originalSteps.size() >= autoCompactedStepCount
                ? keepRecentLogicalItems(originalSteps, config.recentLogicalItemsAfterAutoCompact())
                : List.copyOf(originalSteps);

        compacted = applyToolResultBudget(request, compacted, events);
        compacted = applySnipCompact(request, compacted, events);
        compacted = applyMicroCompact(request, compacted, events);

        int beforeAutoTokens = estimateTokens(request, compacted);
        if (beforeAutoTokens > config.modelInputBudgetTokens()) {
            ContextView autoView = applyAutoCompact(request, compacted, modelClient, events, beforeAutoTokens, originalSteps.size());
            if (autoView.failed()) {
                return autoView;
            }
            return autoView;
        }
        return ContextView.ok(request, compacted, events);
    }

    public ContextView reactiveCompact(AgentRequest request, List<AgentStep> originalSteps, PromptTooLongException cause) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(originalSteps, "originalSteps must not be null");
        int before = estimateTokens(request, originalSteps);
        List<AgentStep> compacted = keepRecentLogicalItems(originalSteps, config.reactiveRecentLogicalItems());
        compacted = microCompactToolResults(compacted, 1);
        int after = estimateTokens(request, compacted);
        ContextCompressionEvent event = new ContextCompressionEvent(
                ContextCompressionEvent.Stage.REACTIVE_COMPACT,
                "provider reported prompt too long",
                before,
                after,
                cause.getMessage()
        );
        return ContextView.ok(request, compacted, List.of(event));
    }

    public int estimateTokens(AgentRequest request, List<AgentStep> steps) {
        int chars = 0;
        chars += request.systemPrompt().length();
        chars += request.task().length();
        for (var entry : request.metadata().entrySet()) {
            chars += entry.getKey().length() + entry.getValue().length();
        }
        for (var message : request.conversationHistory()) {
            chars += message.role().name().length() + message.content().length();
        }
        for (AgentStep step : steps) {
            chars += step.type().name().length();
            chars += step.content().length();
            chars += step.toolCallId() == null ? 0 : step.toolCallId().length();
            chars += step.toolName() == null ? 0 : step.toolName().length();
            chars += step.toolArgumentsOrEmptyObject().toString().length();
        }
        return estimateTokens(chars);
    }

    public static int estimateTokens(String text) {
        return estimateTokens(text == null ? 0 : text.length());
    }

    public static int estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (chars + 3) / 4;
    }

    private List<AgentStep> applyToolResultBudget(
            AgentRequest request,
            List<AgentStep> steps,
            List<ContextCompressionEvent> events
    ) {
        int total = totalToolResultChars(steps);
        if (total <= config.toolResultBudgetChars()) {
            return steps;
        }
        int before = estimateTokens(request, steps);
        List<AgentStep> compacted = new ArrayList<>(steps);
        Set<Integer> protectedIndexes = recentToolResultIndexes(compacted, config.recentToolResultsToKeep());
        while (totalToolResultChars(compacted) > config.toolResultBudgetChars()) {
            int largest = largestToolResultIndex(compacted, protectedIndexes);
            if (largest < 0) {
                break;
            }
            AgentStep step = compacted.get(largest);
            compacted.set(largest, compactToolResult(step, "L3 toolResultBudget"));
        }
        int after = estimateTokens(request, compacted);
        events.add(new ContextCompressionEvent(
                ContextCompressionEvent.Stage.TOOL_RESULT_BUDGET,
                "tool_result total exceeded " + config.toolResultBudgetChars() + " chars",
                before,
                after,
                "large tool results were replaced with placeholders"
        ));
        return List.copyOf(compacted);
    }

    private List<AgentStep> applySnipCompact(
            AgentRequest request,
            List<AgentStep> steps,
            List<ContextCompressionEvent> events
    ) {
        if (steps.size() <= config.maxMessages()) {
            return steps;
        }
        int before = estimateTokens(request, steps);
        int headTarget = Math.max(1, Math.min(8, config.maxMessages() / 4));
        int tailTarget = Math.max(1, config.maxMessages() - headTarget - 1);
        int headEnd = Math.min(headTarget, steps.size());
        if (headEnd < steps.size() && isToolCallResultPair(steps.get(headEnd - 1), steps.get(headEnd))) {
            headEnd++;
        }
        int tailStart = Math.max(headEnd, steps.size() - tailTarget);
        if (tailStart > 0 && tailStart < steps.size() && isToolCallResultPair(steps.get(tailStart - 1), steps.get(tailStart))) {
            tailStart--;
        }
        if (headEnd >= tailStart) {
            return steps;
        }

        List<AgentStep> compacted = new ArrayList<>();
        compacted.addAll(steps.subList(0, headEnd));
        compacted.add(AgentStep.thought("[context snipped by L1 snipCompact: removed "
                + (tailStart - headEnd) + " middle steps]"));
        compacted.addAll(steps.subList(tailStart, steps.size()));
        int after = estimateTokens(request, compacted);
        events.add(new ContextCompressionEvent(
                ContextCompressionEvent.Stage.SNIP_COMPACT,
                "step count exceeded " + config.maxMessages(),
                before,
                after,
                "middle steps were replaced by a snip marker"
        ));
        return List.copyOf(compacted);
    }

    private List<AgentStep> applyMicroCompact(
            AgentRequest request,
            List<AgentStep> steps,
            List<ContextCompressionEvent> events
    ) {
        int before = estimateTokens(request, steps);
        List<AgentStep> compacted = microCompactToolResults(steps, config.recentToolResultsToKeep());
        if (compacted.equals(steps)) {
            return steps;
        }
        int after = estimateTokens(request, compacted);
        events.add(new ContextCompressionEvent(
                ContextCompressionEvent.Stage.MICRO_COMPACT,
                "old tool_result messages compacted; keeping recent "
                        + config.recentToolResultsToKeep(),
                before,
                after,
                "old tool results were replaced by placeholders"
        ));
        return compacted;
    }

    private ContextView applyAutoCompact(
            AgentRequest request,
            List<AgentStep> steps,
            ModelClient modelClient,
            List<ContextCompressionEvent> events,
            int beforeTokens,
            int originalStepCount
    ) {
        try {
            AgentStep summaryStep = modelClient.nextStep(summaryRequest(request), steps, List.of());
            String summary = summaryStep.content().isBlank()
                    ? "Context was compacted at " + Instant.now() + "."
                    : summaryStep.content();
            AgentMemory memory = request.memory().appendSection("Compressed Context", summary);
            AgentRequest memoryRequest = request.withMemory(memory);
            List<AgentStep> recent = keepRecentLogicalItems(steps, config.recentLogicalItemsAfterAutoCompact());
            int afterTokens = estimateTokens(memoryRequest, recent);
            List<ContextCompressionEvent> allEvents = new ArrayList<>(events);
            allEvents.add(new ContextCompressionEvent(
                    ContextCompressionEvent.Stage.AUTO_COMPACT,
                    "estimated tokens exceeded model input budget " + config.modelInputBudgetTokens(),
                    beforeTokens,
                    afterTokens,
                    "LLM summary added to AgentMemory"
            ));
            consecutiveAutoCompactFailures = 0;
            autoCompactedStepCount = originalStepCount;
            return ContextView.ok(memoryRequest, recent, allEvents);
        } catch (RuntimeException e) {
            consecutiveAutoCompactFailures++;
            List<ContextCompressionEvent> allEvents = new ArrayList<>(events);
            allEvents.add(new ContextCompressionEvent(
                    ContextCompressionEvent.Stage.AUTO_COMPACT,
                    "autoCompact failed",
                    beforeTokens,
                    estimateTokens(request, steps),
                    e.getMessage()
            ));
            if (consecutiveAutoCompactFailures >= config.maxAutoCompactFailures()) {
                return ContextView.failed(
                        "autoCompact failed " + consecutiveAutoCompactFailures + " consecutive times: " + e.getMessage(),
                        allEvents
                );
            }
            return ContextView.ok(request, steps, allEvents);
        }
    }

    private AgentRequest summaryRequest(AgentRequest request) {
        String systemPrompt = request.memory().isEmpty()
                ? SUMMARY_SYSTEM_PROMPT
                : SUMMARY_SYSTEM_PROMPT + "\n\n## Existing Memory\n\n" + request.memory().markdown();
        return new AgentRequest(
                "Summarize the provided agent context into durable memory for the same task: " + request.task(),
                1,
                java.util.Map.of(COMPACTION_METADATA_KEY, "auto"),
                systemPrompt,
                request.conversationHistory(),
                request.memory()
        );
    }

    private List<AgentStep> microCompactToolResults(List<AgentStep> steps, int recentToolResultsToKeep) {
        Set<Integer> keep = recentToolResultIndexes(steps, recentToolResultsToKeep);
        List<AgentStep> compacted = new ArrayList<>(steps.size());
        boolean changed = false;
        for (int i = 0; i < steps.size(); i++) {
            AgentStep step = steps.get(i);
            if (step.type() == AgentStep.Type.TOOL_RESULT && !keep.contains(i) && !isCompacted(step)) {
                compacted.add(compactToolResult(step, "L2 microCompact"));
                changed = true;
            } else {
                compacted.add(step);
            }
        }
        return changed ? List.copyOf(compacted) : steps;
    }

    private List<AgentStep> keepRecentLogicalItems(List<AgentStep> steps, int recentLogicalItems) {
        if (steps.isEmpty()) {
            return List.of();
        }
        List<List<AgentStep>> groups = logicalGroups(steps);
        if (groups.size() <= recentLogicalItems) {
            return List.copyOf(steps);
        }
        List<AgentStep> compacted = new ArrayList<>();
        compacted.add(AgentStep.thought("[older context compacted: kept recent "
                + recentLogicalItems + " logical items]"));
        for (List<AgentStep> group : groups.subList(groups.size() - recentLogicalItems, groups.size())) {
            compacted.addAll(group);
        }
        return List.copyOf(compacted);
    }

    private List<List<AgentStep>> logicalGroups(List<AgentStep> steps) {
        List<List<AgentStep>> groups = new ArrayList<>();
        int i = 0;
        while (i < steps.size()) {
            AgentStep current = steps.get(i);
            if (i + 1 < steps.size() && isToolCallResultPair(current, steps.get(i + 1))) {
                groups.add(List.of(current, steps.get(i + 1)));
                i += 2;
            } else {
                groups.add(List.of(current));
                i++;
            }
        }
        return groups;
    }

    private boolean isToolCallResultPair(AgentStep first, AgentStep second) {
        if (first.type() != AgentStep.Type.TOOL_CALL || second.type() != AgentStep.Type.TOOL_RESULT) {
            return false;
        }
        if (first.toolCallId() != null && second.toolCallId() != null) {
            return first.toolCallId().equals(second.toolCallId());
        }
        return Objects.equals(first.toolName(), second.toolName());
    }

    private int totalToolResultChars(List<AgentStep> steps) {
        int total = 0;
        for (AgentStep step : steps) {
            if (step.type() == AgentStep.Type.TOOL_RESULT) {
                total += step.content().length();
            }
        }
        return total;
    }

    private int largestToolResultIndex(List<AgentStep> steps, Set<Integer> excluded) {
        int largestIndex = -1;
        int largestLength = -1;
        for (int i = 0; i < steps.size(); i++) {
            AgentStep step = steps.get(i);
            if (step.type() == AgentStep.Type.TOOL_RESULT
                    && !excluded.contains(i)
                    && !isCompacted(step)
                    && step.content().length() > largestLength) {
                largestIndex = i;
                largestLength = step.content().length();
            }
        }
        return largestIndex;
    }

    private Set<Integer> recentToolResultIndexes(List<AgentStep> steps, int recentToolResultsToKeep) {
        Set<Integer> keep = new HashSet<>();
        for (int i = steps.size() - 1; i >= 0 && keep.size() < recentToolResultsToKeep; i--) {
            if (steps.get(i).type() == AgentStep.Type.TOOL_RESULT) {
                keep.add(i);
            }
        }
        return keep;
    }

    private AgentStep compactToolResult(AgentStep step, String stage) {
        return AgentStep.toolResult(
                step.toolCallId(),
                step.toolNameOptional().orElse("unknown"),
                "[tool_result compacted by " + stage
                        + ": tool=" + step.toolNameOptional().orElse("unknown")
                        + ", originalLength=" + step.content().length()
                        + "]"
        );
    }

    private boolean isCompacted(AgentStep step) {
        return step.content().startsWith("[tool_result compacted by ");
    }

    public record ContextView(
            AgentRequest request,
            List<AgentStep> steps,
            List<ContextCompressionEvent> events,
            String failureMessage
    ) {

        public ContextView {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(steps, "steps must not be null");
            Objects.requireNonNull(events, "events must not be null");
            steps = List.copyOf(steps);
            events = List.copyOf(events);
            failureMessage = failureMessage == null ? "" : failureMessage;
        }

        public static ContextView ok(AgentRequest request, List<AgentStep> steps, List<ContextCompressionEvent> events) {
            return new ContextView(request, steps, events, "");
        }

        public static ContextView failed(String message, List<ContextCompressionEvent> events) {
            return new ContextView(
                    AgentRequest.of("context compression failed"),
                    List.of(AgentStep.error(message)),
                    events,
                    message
            );
        }

        public boolean failed() {
            return !failureMessage.isBlank();
        }
    }
}
