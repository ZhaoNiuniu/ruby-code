package io.github.mengru.agent.core.scheduler;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

public final class CronQueue {

    private final LinkedBlockingQueue<CronTriggeredTask> queue = new LinkedBlockingQueue<>();
    private final Set<String> pendingJobIds = new HashSet<>();
    private final Set<String> runningJobIds = new HashSet<>();

    public boolean enqueue(CronTriggeredTask task) {
        Objects.requireNonNull(task, "task must not be null");
        synchronized (this) {
            String jobId = task.job().jobId();
            if (pendingJobIds.contains(jobId) || runningJobIds.contains(jobId)) {
                return false;
            }
            pendingJobIds.add(jobId);
            queue.add(task);
            return true;
        }
    }

    public CronTriggeredTask take() throws InterruptedException {
        CronTriggeredTask task = queue.take();
        synchronized (this) {
            pendingJobIds.remove(task.job().jobId());
            runningJobIds.add(task.job().jobId());
        }
        return task;
    }

    public synchronized void complete(String jobId) {
        if (jobId != null) {
            runningJobIds.remove(jobId);
        }
    }

    public synchronized String status(String jobId) {
        if (runningJobIds.contains(jobId)) {
            return "running";
        }
        if (pendingJobIds.contains(jobId)) {
            return "pending";
        }
        return "idle";
    }

    public int size() {
        return queue.size();
    }
}
