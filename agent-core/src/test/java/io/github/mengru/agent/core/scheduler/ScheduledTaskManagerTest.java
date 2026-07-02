package io.github.mengru.agent.core.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskManagerTest {

    @TempDir
    Path workspace;

    @Test
    void durableJobIsWrittenLoadedAndIgnoredByGit() throws IOException {
        CronQueue queue = new CronQueue();
        ScheduledTaskManager manager = managerAt("2026-07-01T00:00:00Z", queue);

        ScheduledJobView view = manager.schedule(new ScheduledJobSpec(
                "heartbeat",
                "say hi",
                ScheduledJobType.CRON,
                true,
                "*/10 * * * * *",
                null,
                ZoneId.of("UTC")
        ));

        assertThat(view.job().jobId()).isEqualTo("job_1");
        assertThat(Files.exists(workspace.resolve(ScheduledJobStore.FILE_NAME))).isTrue();
        assertThat(read(workspace.resolve(".gitignore"))).contains(ScheduledJobStore.FILE_NAME);

        ScheduledTaskManager reloaded = managerAt("2026-07-01T00:00:01Z", new CronQueue());

        assertThat(reloaded.listJobs())
                .extracting(job -> job.job().name())
                .containsExactly("heartbeat");
    }

    @Test
    void invalidDurableJobIsSkippedWithWarning() throws IOException {
        Files.writeString(workspace.resolve(ScheduledJobStore.FILE_NAME), """
                {
                  "version": 1,
                  "jobs": [
                    {"jobId":"job_7","name":"bad","task":"oops","type":"cron","cronExpression":"? * * * * *","zoneId":"UTC"},
                    {"jobId":"job_8","name":"good","task":"ok","type":"cron","cronExpression":"*/5 * * * * *","zoneId":"UTC"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        ScheduledTaskManager manager = managerAt("2026-07-01T00:00:00Z", new CronQueue());

        assertThat(manager.warnings()).anyMatch(warning -> warning.contains("Skipped scheduled job"));
        assertThat(manager.listJobs())
                .extracting(job -> job.job().jobId())
                .containsExactly("job_8");
    }

    @Test
    void dueOnceJobTriggersOnceAndIsRemoved() {
        CronQueue queue = new CronQueue();
        ScheduledTaskManager manager = managerAt("2026-07-01T00:00:10Z", queue);
        manager.schedule(new ScheduledJobSpec(
                "once",
                "do once",
                ScheduledJobType.ONCE,
                false,
                "",
                Instant.parse("2026-07-01T00:00:05Z"),
                ZoneId.of("UTC")
        ));

        List<CronTriggeredTask> due = manager.collectDueTasks();

        assertThat(due).hasSize(1);
        assertThat(due.get(0).job().name()).isEqualTo("once");
        assertThat(manager.listJobs()).isEmpty();
    }

    @Test
    void cronQueueMergesSameJobWhilePendingOrRunning() throws InterruptedException {
        CronQueue queue = new CronQueue();
        ScheduledJob job = new ScheduledJob(
                "job_1",
                "fast",
                "task",
                ScheduledJobType.CRON,
                false,
                "* * * * * *",
                null,
                ZoneId.of("UTC")
        );

        assertThat(queue.enqueue(new CronTriggeredTask(job, Instant.parse("2026-07-01T00:00:00Z")))).isTrue();
        assertThat(queue.enqueue(new CronTriggeredTask(job, Instant.parse("2026-07-01T00:00:01Z")))).isFalse();
        CronTriggeredTask task = queue.take();
        assertThat(task.job().jobId()).isEqualTo("job_1");
        assertThat(queue.status("job_1")).isEqualTo("running");
        assertThat(queue.enqueue(new CronTriggeredTask(job, Instant.parse("2026-07-01T00:00:02Z")))).isFalse();
        queue.complete("job_1");
        assertThat(queue.enqueue(new CronTriggeredTask(job, Instant.parse("2026-07-01T00:00:03Z")))).isTrue();
    }

    private ScheduledTaskManager managerAt(String instant, CronQueue queue) {
        return new ScheduledTaskManager(
                new ScheduledJobStore(workspace),
                queue,
                Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"))
        );
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
