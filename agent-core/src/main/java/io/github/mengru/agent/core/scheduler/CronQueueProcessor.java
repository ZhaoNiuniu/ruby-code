package io.github.mengru.agent.core.scheduler;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.core.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public final class CronQueueProcessor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CronQueueProcessor.class);

    private final CronQueue queue;
    private final AgentSession session;
    private final Function<CronTriggeredTask, AgentRequest> requestFactory;
    private final Consumer<CronExecutionResult> resultConsumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public CronQueueProcessor(
            CronQueue queue,
            AgentSession session,
            Function<CronTriggeredTask, AgentRequest> requestFactory,
            Consumer<CronExecutionResult> resultConsumer
    ) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        this.resultConsumer = Objects.requireNonNull(resultConsumer, "resultConsumer must not be null");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "agent-cron-queue-processor");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop() {
        while (running.get()) {
            CronTriggeredTask task = null;
            try {
                task = queue.take();
                AgentResult result = session.run(requestFactory.apply(task));
                resultConsumer.accept(new CronExecutionResult(task, result, ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (RuntimeException e) {
                LOG.warn("Cron queue processor failed: {}", e.getMessage());
                if (task != null) {
                    resultConsumer.accept(new CronExecutionResult(task, null, e.getMessage()));
                }
            } finally {
                if (task != null) {
                    queue.complete(task.job().jobId());
                }
            }
        }
    }
}
