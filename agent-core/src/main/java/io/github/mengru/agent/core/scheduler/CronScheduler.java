package io.github.mengru.agent.core.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CronScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CronScheduler.class);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private final ScheduledTaskManager manager;
    private final CronQueue queue;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public CronScheduler(ScheduledTaskManager manager, CronQueue queue) {
        this(manager, queue, DEFAULT_POLL_INTERVAL);
    }

    CronScheduler(ScheduledTaskManager manager, CronQueue queue, Duration pollInterval) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "agent-cron-scheduler");
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
            try {
                for (CronTriggeredTask task : manager.collectDueTasks()) {
                    queue.enqueue(task);
                }
            } catch (RuntimeException e) {
                LOG.warn("Cron scheduler tick failed: {}", e.getMessage());
            }
            sleep();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }
}
