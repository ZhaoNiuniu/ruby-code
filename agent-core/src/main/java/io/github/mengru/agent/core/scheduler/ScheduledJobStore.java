package io.github.mengru.agent.core.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class ScheduledJobStore {

    public static final String FILE_NAME = ".scheduled_tasks.json";

    private final Path workspace;
    private final ObjectMapper objectMapper;

    public ScheduledJobStore(Path workspace) {
        this(workspace, new ObjectMapper());
    }

    ScheduledJobStore(Path workspace, ObjectMapper objectMapper) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null")
                .toAbsolutePath()
                .normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public static ScheduledJobStore defaultStore() {
        return new ScheduledJobStore(Path.of(""));
    }

    public Path file() {
        return workspace.resolve(FILE_NAME);
    }

    public ScheduledJobLoadResult load() {
        if (!Files.exists(file())) {
            return new ScheduledJobLoadResult(List.of(), List.of());
        }
        List<ScheduledJob> jobs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file(), StandardCharsets.UTF_8));
            JsonNode jobNodes = root.path("jobs");
            if (!jobNodes.isArray()) {
                warnings.add(FILE_NAME + " does not contain a jobs array");
                return new ScheduledJobLoadResult(jobs, warnings);
            }
            for (int i = 0; i < jobNodes.size(); i++) {
                try {
                    jobs.add(parseJob(jobNodes.get(i)));
                } catch (RuntimeException e) {
                    warnings.add("Skipped scheduled job at index " + i + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            warnings.add("Failed to read " + FILE_NAME + ": " + e.getMessage());
        }
        return new ScheduledJobLoadResult(jobs, warnings);
    }

    public void save(Collection<ScheduledJob> jobs) {
        Objects.requireNonNull(jobs, "jobs must not be null");
        try {
            List<ScheduledJob> durableJobs = jobs.stream()
                    .filter(ScheduledJob::durable)
                    .toList();
            if (durableJobs.isEmpty()) {
                Files.deleteIfExists(file());
                return;
            }
            ensureGitIgnore();
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", 1);
            ArrayNode jobNodes = root.putArray("jobs");
            for (ScheduledJob job : durableJobs) {
                jobNodes.add(renderJob(job));
            }
            Files.writeString(
                    file(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save scheduled jobs: " + e.getMessage(), e);
        }
    }

    public void ensureGitIgnore() {
        Path gitIgnore = workspace.resolve(".gitignore");
        try {
            if (!Files.exists(gitIgnore)) {
                Files.writeString(gitIgnore, FILE_NAME + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            String current = Files.readString(gitIgnore, StandardCharsets.UTF_8);
            if (hasLine(current, FILE_NAME)) {
                return;
            }
            String prefix = current.endsWith("\n") || current.isBlank() ? current : current + "\n";
            Files.writeString(gitIgnore, prefix + FILE_NAME + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update .gitignore: " + e.getMessage(), e);
        }
    }

    private ScheduledJob parseJob(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("job must be an object");
        }
        String jobId = requiredText(node, "jobId");
        String task = requiredText(node, "task");
        ScheduledJobType type = ScheduledJobType.from(requiredText(node, "type"));
        String name = optionalText(node, "name");
        String cronExpression = optionalText(node, "cronExpression");
        Instant runAt = parseOptionalInstant(node, "runAt");
        ZoneId zoneId = parseZone(optionalText(node, "zoneId"));
        ScheduledJob job = new ScheduledJob(jobId, name, task, type, true, cronExpression, runAt, zoneId);
        validate(job);
        return job;
    }

    private ObjectNode renderJob(ScheduledJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.jobId());
        node.put("name", job.name());
        node.put("task", job.task());
        node.put("type", job.type().value());
        node.put("durable", true);
        node.put("zoneId", job.zoneId().getId());
        if (job.type() == ScheduledJobType.CRON) {
            node.put("cronExpression", job.cronExpression());
        }
        if (job.type() == ScheduledJobType.ONCE) {
            node.put("runAt", job.runAt().toString());
        }
        return node;
    }

    private void validate(ScheduledJob job) {
        if (job.type() == ScheduledJobType.CRON) {
            CronExpression.parse(job.cronExpression());
            return;
        }
        Objects.requireNonNull(job.runAt(), "runAt is required for once jobs");
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    private Instant parseOptionalInstant(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant: " + value, e);
        }
    }

    private ZoneId parseZone(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("zoneId is invalid: " + value, e);
        }
    }

    private static boolean hasLine(String content, String expected) {
        for (String line : content.split("\n")) {
            if (line.strip().equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
