package io.github.mengru.agent.core.audit;

import io.github.mengru.agent.api.AuditEvent;

import java.util.List;

public final class CompositeRunAuditSink implements RunAuditSink {

    private final List<RunAuditSink> sinks;

    public CompositeRunAuditSink(List<RunAuditSink> sinks) {
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
    }

    public static RunAuditSink of(List<RunAuditSink> sinks) {
        List<RunAuditSink> actual = sinks == null ? List.of() : sinks.stream()
                .filter(sink -> sink != null && sink != RunAuditSink.noop())
                .toList();
        if (actual.isEmpty()) {
            return RunAuditSink.noop();
        }
        if (actual.size() == 1) {
            return actual.get(0);
        }
        return new CompositeRunAuditSink(actual);
    }

    @Override
    public void emit(AuditEvent event) {
        for (RunAuditSink sink : sinks) {
            sink.emit(event);
        }
    }

    @Override
    public void closeRun(RunAuditSummary summary) {
        for (RunAuditSink sink : sinks) {
            sink.closeRun(summary);
        }
    }
}
