package io.github.mengru.agent.core.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScheduledTaskManager {

    private static final Pattern JOB_ID_SUFFIX = Pattern.compile("^job_(\\d+)$");

    private final ScheduledJobStore store;
    private final CronQueue queue;
    private final Clock clock;
    private final Map<String, JobState> jobsById = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong();

    public ScheduledTaskManager(ScheduledJobStore store, CronQueue queue) {
        this(store, queue, Clock.systemDefaultZone());
    }

    ScheduledTaskManager(ScheduledJobStore store, CronQueue queue, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        ScheduledJobLoadResult loaded = store.load();
        warnings.addAll(loaded.warnings());
        Instant now = clock.instant();
        for (ScheduledJob job : loaded.jobs()) {
            try {
                addLoadedJob(job, now);
                observeJobId(job.jobId());
            } catch (RuntimeException e) {
                warnings.add("Skipped scheduled job " + job.jobId() + ": " + e.getMessage());
            }
        }
        if (!loaded.jobs().isEmpty() || !loaded.warnings().isEmpty() || java.nio.file.Files.exists(store.file())) {
            store.ensureGitIgnore();
        }
    }

    public synchronized ScheduledJobView schedule(ScheduledJobSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        String jobId = nextJobId();
        ScheduledJob job = new ScheduledJob(
                jobId,
                spec.name(),
                spec.task(),
                Objects.requireNonNull(spec.type(), "type must not be null"),
                spec.durable(),
                spec.cronExpression(),
                spec.runAt(),
                spec.zoneId()
        );
        Instant nextRunAt = nextRunAt(job, clock.instant(), true);
        jobsById.put(jobId, new JobState(job, nextRunAt));
        persistDurableJobs();
        return new ScheduledJobView(job, nextRunAt, queue.status(jobId));
    }

    public synchronized boolean cancel(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        JobState removed = jobsById.remove(jobId);
        if (removed != null) {
            persistDurableJobs();
            return true;
        }
        return false;
    }

    public synchronized List<ScheduledJobView> listJobs() {
        return jobsById.values().stream()
                .sorted(Comparator.comparing(state -> state.job().jobId()))
                .map(state -> new ScheduledJobView(state.job(), state.nextRunAt(), queue.status(state.job().jobId())))
                .toList();
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized List<CronTriggeredTask> collectDueTasks() {
        Instant now = clock.instant();
        List<CronTriggeredTask> dueTasks = new ArrayList<>();
        List<String> onceJobsToRemove = new ArrayList<>();
        boolean changed = false;
        for (JobState state : new ArrayList<>(jobsById.values())) {
            try {
                if (now.isBefore(state.nextRunAt())) {
                    continue;
                }
                ScheduledJob job = state.job();
                dueTasks.add(new CronTriggeredTask(job, now));
                if (job.type() == ScheduledJobType.ONCE) {
                    onceJobsToRemove.add(job.jobId());
                    changed = true;
                } else {
                    state.setNextRunAt(nextRunAt(job, now, false));
                }
            } catch (RuntimeException e) {
                warnings.add("Scheduled job " + state.job().jobId() + " failed during due check: " + e.getMessage());
            }
        }
        for (String jobId : onceJobsToRemove) {
            jobsById.remove(jobId);
        }
        if (changed) {
            persistDurableJobs();
        }
        return dueTasks;
    }

    private void addLoadedJob(ScheduledJob job, Instant now) {
        Instant nextRunAt = nextRunAt(job, now, true);
        jobsById.put(job.jobId(), new JobState(job, nextRunAt));
    }

    private Instant nextRunAt(ScheduledJob job, Instant now, boolean loadedOrNew) {
        if (job.type() == ScheduledJobType.ONCE) {
            if (!loadedOrNew || job.runAt().isAfter(now)) {
                return job.runAt();
            }
            return now;
        }
        return CronExpression.parse(job.cronExpression()).nextAfter(now, job.zoneId());
    }

    private void persistDurableJobs() {
        store.save(jobsById.values().stream().map(JobState::job).toList());
    }

    private String nextJobId() {
        long id = nextId.incrementAndGet();
        while (jobsById.containsKey("job_" + id)) {
            id = nextId.incrementAndGet();
        }
        return "job_" + id;
    }

    private void observeJobId(String jobId) {
        Matcher matcher = JOB_ID_SUFFIX.matcher(jobId);
        if (matcher.matches()) {
            try {
                long value = Long.parseLong(matcher.group(1));
                nextId.updateAndGet(current -> Math.max(current, value));
            } catch (NumberFormatException ignored) {
                // Ignore non-numeric suffixes.
            }
        }
    }

    private static final class JobState {
        private final ScheduledJob job;
        private Instant nextRunAt;

        private JobState(ScheduledJob job, Instant nextRunAt) {
            this.job = Objects.requireNonNull(job, "job must not be null");
            this.nextRunAt = Objects.requireNonNull(nextRunAt, "nextRunAt must not be null");
        }

        private ScheduledJob job() {
            return job;
        }

        private Instant nextRunAt() {
            return nextRunAt;
        }

        private void setNextRunAt(Instant nextRunAt) {
            this.nextRunAt = Objects.requireNonNull(nextRunAt, "nextRunAt must not be null");
        }
    }
}
