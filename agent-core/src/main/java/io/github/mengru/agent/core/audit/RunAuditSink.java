package io.github.mengru.agent.core.audit;

import io.github.mengru.agent.api.AuditEvent;

public interface RunAuditSink {

    void emit(AuditEvent event);

    void closeRun(RunAuditSummary summary);

    static RunAuditSink noop() {
        return NoopRunAuditSink.INSTANCE;
    }
}
