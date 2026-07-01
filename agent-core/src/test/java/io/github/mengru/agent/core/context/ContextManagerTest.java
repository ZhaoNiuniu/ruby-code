package io.github.mengru.agent.core.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ContextCompressionEvent;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.PromptTooLongException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagerTest {

    @Test
    void microCompactKeepsRecentToolResultsAndDoesNotMutateOriginalSteps() {
        ContextManager manager = ContextManager.defaults();
        List<AgentStep> steps = toolPairs(4, "important-output-");

        ContextManager.ContextView view = manager.prepareForModel(
                AgentRequest.of("task"),
                steps,
                finalAnswerModel("ok"),
                List.of()
        );

        assertThat(view.events())
                .extracting(ContextCompressionEvent::stage)
                .contains(ContextCompressionEvent.Stage.MICRO_COMPACT);
        assertThat(view.steps().get(1).content()).contains("L2 microCompact");
        assertThat(view.steps().get(3).content()).contains("important-output-1");
        assertThat(steps.get(1).content()).contains("important-output-0");
    }

    @Test
    void toolResultBudgetRunsBeforeMicroCompact() {
        ContextManager manager = ContextManager.withConfig(config(10_000, 4_000, 1_000, 80, 50));
        List<AgentStep> steps = toolPairs(4, "very-large-output-".repeat(20));

        ContextManager.ContextView view = manager.prepareForModel(
                AgentRequest.of("task"),
                steps,
                finalAnswerModel("ok"),
                List.of()
        );

        assertThat(view.events())
                .extracting(ContextCompressionEvent::stage)
                .startsWith(ContextCompressionEvent.Stage.TOOL_RESULT_BUDGET);
    }

    @Test
    void snipCompactReplacesMiddleStepsWhenStepCountExceedsThreshold() {
        ContextManager manager = ContextManager.withConfig(config(10_000, 4_000, 1_000, 200_000, 10));
        List<AgentStep> steps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            steps.add(AgentStep.thought("thought-" + i));
        }

        ContextManager.ContextView view = manager.prepareForModel(
                AgentRequest.of("task"),
                steps,
                finalAnswerModel("ok"),
                List.of()
        );

        assertThat(view.events())
                .extracting(ContextCompressionEvent::stage)
                .contains(ContextCompressionEvent.Stage.SNIP_COMPACT);
        assertThat(view.steps())
                .extracting(AgentStep::content)
                .anyMatch(content -> content.contains("snipCompact"));
    }

    @Test
    void autoCompactUsesModelWithoutToolsAndWritesMemory() {
        AtomicReference<List<?>> seenTools = new AtomicReference<>();
        AtomicReference<String> seenMetadata = new AtomicReference<>();
        ModelClient summarizer = (request, previousSteps, tools) -> {
            seenTools.set(tools);
            seenMetadata.set(request.metadata().get("agent.contextCompression"));
            return AgentStep.finalAnswer("## Goal\n\nKeep the bug fix focused.");
        };
        ContextManager manager = ContextManager.withConfig(config(200, 20, 20, 200_000, 50));

        ContextManager.ContextView view = manager.prepareForModel(
                AgentRequest.of("task"),
                toolPairs(6, "large-output-".repeat(80)),
                summarizer,
                List.of(new io.github.mengru.agent.core.EchoTool())
        );

        assertThat(seenTools.get()).isEmpty();
        assertThat(seenMetadata.get()).isEqualTo("auto");
        assertThat(view.request().memory().markdown()).contains("Keep the bug fix focused");
        assertThat(view.events())
                .extracting(ContextCompressionEvent::stage)
                .contains(ContextCompressionEvent.Stage.AUTO_COMPACT);
    }

    @Test
    void autoCompactFailsAfterThreeConsecutiveSummaryFailures() {
        ContextManager manager = ContextManager.withConfig(config(200, 20, 20, 200_000, 50));
        ModelClient failing = (request, previousSteps, tools) -> {
            throw new IllegalStateException("summary down");
        };
        List<AgentStep> steps = toolPairs(6, "large-output-".repeat(80));

        assertThat(manager.prepareForModel(AgentRequest.of("task"), steps, failing, List.of()).failed()).isFalse();
        assertThat(manager.prepareForModel(AgentRequest.of("task"), steps, failing, List.of()).failed()).isFalse();
        ContextManager.ContextView third = manager.prepareForModel(AgentRequest.of("task"), steps, failing, List.of());

        assertThat(third.failed()).isTrue();
        assertThat(third.failureMessage()).contains("autoCompact failed 3 consecutive times");
    }

    @Test
    void reactiveCompactKeepsRecentLogicalToolPairs() {
        ContextManager manager = ContextManager.defaults();

        ContextManager.ContextView view = manager.reactiveCompact(
                AgentRequest.of("task"),
                toolPairs(8, "output-"),
                new PromptTooLongException("too long")
        );

        assertThat(view.events())
                .extracting(ContextCompressionEvent::stage)
                .containsExactly(ContextCompressionEvent.Stage.REACTIVE_COMPACT);
        assertThat(view.steps())
                .extracting(AgentStep::content)
                .anyMatch(content -> content.contains("older context compacted"));
        assertThat(view.steps())
                .extracting(AgentStep::toolCallId)
                .contains("call-7");
    }

    private static ContextCompressionConfig config(
            int contextWindowTokens,
            int maxOutputTokens,
            int reservedTokens,
            int toolResultBudgetChars,
            int maxMessages
    ) {
        return new ContextCompressionConfig(
                true,
                contextWindowTokens,
                maxOutputTokens,
                reservedTokens,
                toolResultBudgetChars,
                maxMessages,
                ContextCompressionConfig.DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP,
                ContextCompressionConfig.DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT,
                ContextCompressionConfig.DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS,
                ContextCompressionConfig.DEFAULT_MAX_AUTO_COMPACT_FAILURES
        );
    }

    private static ModelClient finalAnswerModel(String output) {
        return (request, previousSteps, tools) -> AgentStep.finalAnswer(output);
    }

    private static List<AgentStep> toolPairs(int count, String outputPrefix) {
        List<AgentStep> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add(AgentStep.toolCall("call-" + i, "read_file", JsonNodeFactory.instance.objectNode()));
            steps.add(AgentStep.toolResult("call-" + i, "read_file", outputPrefix + i));
        }
        return List.copyOf(steps);
    }
}
