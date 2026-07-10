package io.github.mengru.agent.core.audit;

import io.github.mengru.agent.api.AuditEvent;

final class NoopRunAuditSink implements RunAuditSink {

    static final NoopRunAuditSink INSTANCE = new NoopRunAuditSink();

    private NoopRunAuditSink() {
    }

    @Override
    public void emit(AuditEvent event) {
    }

    @Override
    public void closeRun(RunAuditSummary summary) {
    }
}
