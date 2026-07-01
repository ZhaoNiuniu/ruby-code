package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.core.memory.MemoryExtractor;
import io.github.mengru.agent.core.memory.MemoryStore;

import java.util.Objects;

public final class MemoryExtractionHook implements AgentHook<StopContext> {

    private final MemoryExtractor extractor;
    private final MemoryStore store;

    public MemoryExtractionHook(MemoryExtractor extractor, MemoryStore store) {
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public HookResult<StopContext> apply(StopContext context) {
        if (!context.result().completed()) {
            return HookResult.continueWith(context);
        }
        try {
            extractor.extract(context.request(), context.result()).ifPresent(store::save);
        } catch (RuntimeException ignored) {
            return HookResult.continueWith(context);
        }
        return HookResult.continueWith(context);
    }
}
