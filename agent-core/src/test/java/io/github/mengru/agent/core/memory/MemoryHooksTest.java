package io.github.mengru.agent.core.memory;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.core.hook.MemoryExtractionHook;
import io.github.mengru.agent.core.hook.StopContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryHooksTest {

    @TempDir
    Path workspace;

    @Test
    void extractionHookWritesOnlyCompletedResults() {
        MemoryStore store = new MemoryStore(workspace);
        MemoryExtractor extractor = new MemoryExtractor((request, previousSteps, tools) -> AgentStep.finalAnswer("""
                ---
                name: tab-style
                description: Use tab indentation
                type: user
                ---
                
                Use tabs instead of spaces.
                """));
        MemoryExtractionHook hook = new MemoryExtractionHook(extractor, store);

        hook.apply(new StopContext(
                AgentRequest.of("请记住用 tab 不用空格"),
                List.of(),
                new AgentResult("ok", List.of(AgentStep.finalAnswer("ok")), true),
                "final-answer"
        ));
        hook.apply(new StopContext(
                AgentRequest.of("请记住失败的内容"),
                List.of(),
                new AgentResult("failed", List.of(), false),
                "max-steps"
        ));

        MemoryCatalog catalog = MemoryCatalog.scan(workspace);
        assertThat(catalog.memories()).hasSize(1);
        assertThat(catalog.memories().get(0).name()).isEqualTo("tab-style");
    }
}
