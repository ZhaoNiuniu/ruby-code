package io.github.mengru.agent.core.trace;

import io.github.mengru.agent.api.TraceEvent;

@FunctionalInterface
public interface TraceSink {

    void emit(TraceEvent event);

    static TraceSink noop() {
        return event -> {
        };
    }
}
