package io.github.mengru.agent.core.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TaskManager {

    public static final String AGENT_NAME_METADATA_KEY = "agent.name";
    public static final String TASKS_DIR_NAME = ".tasks";
    public static final String HIGH_WATERMARK_FILE_NAME = ".highwatermark";
    public static final String LOCK_FILE_NAME = ".lock";

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^task_(\\d{6})$");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path workspace;

    public TaskManager(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
    }

    public static TaskManager defaultManager() {
        return new TaskManager(Path.of(""));
    }

    public Path tasksDir() {
        return workspace.resolve(TASKS_DIR_NAME);
    }

    public TaskLoadResult load() {
        return loadUnlocked();
    }

    public TaskDefinition create(String subject, String description, List<String> blockedBy) {
        return withLock(() -> {
            TaskLoadResult loaded = loadUnlocked();
            long nextId = nextId(loaded.tasksById());
            writeHighWatermark(nextId);
            TaskDefinition task = new TaskDefinition(
                    formatTaskId(nextId),
                    subject,
                    description,
                    TaskStatus.PENDING,
                    "",
                    normalizeDependencies(blockedBy)
            );
            writeTask(task);
            return task;
        });
    }

    public ListTasksResult list(String statusFilter, String ownerFilter, Boolean canStartFilter) {
        TaskLoadResult loaded = loadUnlocked();
        TaskStatus status = statusFilter == null || statusFilter.isBlank() ? null : TaskStatus.from(statusFilter);
        String owner = ownerFilter == null ? "" : ownerFilter.strip();
        List<TaskSummary> summaries = new ArrayList<>();
        for (TaskDefinition task : sortedTasks(loaded.tasksById())) {
            CanStartResult canStart = canStart(task.id(), loaded);
            if (status != null && task.status() != status) {
                continue;
            }
            if (!owner.isBlank() && !owner.equals(task.owner())) {
                continue;
            }
            if (canStartFilter != null && canStart.canStart() != canStartFilter) {
                continue;
            }
            summaries.add(new TaskSummary(task, canStart.canStart()));
        }
        return new ListTasksResult(summaries, loaded.warnings());
    }

    public TaskDetail get(String taskId) {
        TaskLoadResult loaded = loadUnlocked();
        TaskDefinition task = findTask(taskId, loaded);
        return new TaskDetail(task, canStart(task.id(), loaded), loaded.warnings());
    }

    public CanStartResult canStart(String taskId) {
        return canStart(taskId, loadUnlocked());
    }

    public TaskDefinition claim(String taskId, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        return withLock(() -> {
            TaskLoadResult loaded = loadUnlocked();
            TaskDefinition task = findTask(taskId, loaded);
            if (task.status() != TaskStatus.PENDING) {
                throw new IllegalStateException("task is not pending: " + task.id());
            }
            CanStartResult canStart = canStart(task.id(), loaded);
            if (!canStart.canStart()) {
                throw new IllegalStateException("task is blocked: " + canStart.reason());
            }
            TaskDefinition updated = task.withStatus(TaskStatus.IN_PROGRESS, normalizedOwner);
            writeTask(updated);
            return updated;
        });
    }

    public CompleteTaskResult complete(String taskId, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        return withLock(() -> {
            TaskLoadResult loaded = loadUnlocked();
            TaskDefinition task = findTask(taskId, loaded);
            if (task.status() != TaskStatus.IN_PROGRESS) {
                throw new IllegalStateException("task is not in_progress: " + task.id());
            }
            if (!normalizedOwner.equals(task.owner())) {
                throw new IllegalStateException("task owner mismatch: expected " + task.owner() + ", got " + normalizedOwner);
            }
            TaskDefinition completed = task.withStatus(TaskStatus.COMPLETED, task.owner());
            writeTask(completed);

            LinkedHashMap<String, TaskDefinition> refreshed = new LinkedHashMap<>(loaded.tasksById());
            refreshed.put(completed.id(), completed);
            List<TaskDefinition> unlocked = new ArrayList<>();
            TaskLoadResult updatedLoad = new TaskLoadResult(refreshed, loaded.warnings());
            for (TaskDefinition downstream : sortedTasks(refreshed)) {
                if (downstream.status() == TaskStatus.PENDING
                        && downstream.blockedBy().contains(completed.id())
                        && canStart(downstream.id(), updatedLoad).canStart()) {
                    unlocked.add(downstream);
                }
            }
            return new CompleteTaskResult(completed, unlocked);
        });
    }

    public ObjectNode toJson(TaskDefinition task) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", task.id());
        node.put("subject", task.subject());
        node.put("description", task.description());
        node.put("status", task.status().value());
        if (task.owner().isBlank()) {
            node.putNull("owner");
        } else {
            node.put("owner", task.owner());
        }
        ArrayNode dependencies = node.putArray("blockedBy");
        task.blockedBy().forEach(dependencies::add);
        return node;
    }

    public ObjectNode canStartToJson(CanStartResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("taskId", result.taskId());
        node.put("canStart", result.canStart());
        node.put("reason", result.reason());
        ArrayNode dependencies = node.putArray("dependencies");
        for (TaskDependencyStatus dependency : result.dependencies()) {
            ObjectNode item = dependencies.addObject();
            item.put("taskId", dependency.taskId());
            item.put("exists", dependency.exists());
            item.put("status", dependency.status());
            item.put("completed", dependency.completed());
        }
        appendWarnings(node, result.warnings());
        return node;
    }

    public void appendWarnings(ObjectNode node, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        ArrayNode warningNodes = node.putArray("warnings");
        warnings.forEach(warningNodes::add);
    }

    private TaskLoadResult loadUnlocked() {
        if (!Files.exists(tasksDir())) {
            return new TaskLoadResult(Map.of(), List.of());
        }
        LinkedHashMap<String, TaskDefinition> tasks = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try (Stream<Path> stream = Files.list(tasksDir())) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                try {
                    TaskDefinition task = parseTask(file);
                    TaskDefinition previous = tasks.putIfAbsent(task.id(), task);
                    if (previous != null) {
                        warnings.add("Skipped duplicate task id " + task.id() + " in " + relative(file));
                    }
                } catch (RuntimeException | IOException e) {
                    warnings.add("Skipped invalid task file " + relative(file) + ": " + e.getMessage());
                }
            }
            return new TaskLoadResult(tasks, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list .tasks: " + e.getMessage(), e);
        }
    }

    private TaskDefinition parseTask(Path file) throws IOException {
        JsonNode node = MAPPER.readTree(file.toFile());
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("task JSON must be an object");
        }
        String id = requiredText(node, "id");
        validateTaskId(id);
        String subject = requiredText(node, "subject");
        String description = requiredText(node, "description");
        TaskStatus status = TaskStatus.from(requiredText(node, "status"));
        JsonNode ownerNode = node.get("owner");
        String owner = ownerNode == null || ownerNode.isNull() ? "" : ownerNode.asText("").strip();
        JsonNode blockedByNode = node.get("blockedBy");
        List<String> blockedBy = new ArrayList<>();
        if (blockedByNode != null && !blockedByNode.isNull()) {
            if (!blockedByNode.isArray()) {
                throw new IllegalArgumentException("blockedBy must be an array");
            }
            for (int i = 0; i < blockedByNode.size(); i++) {
                JsonNode dependency = blockedByNode.get(i);
                if (!dependency.isTextual() || dependency.asText().isBlank()) {
                    throw new IllegalArgumentException("blockedBy[" + i + "] must be a non-empty string");
                }
                blockedBy.add(dependency.asText().strip());
            }
        }
        return new TaskDefinition(id, subject, description, status, owner, blockedBy);
    }

    private CanStartResult canStart(String taskId, TaskLoadResult loaded) {
        TaskDefinition task = findTask(taskId, loaded);
        if (task.status() != TaskStatus.PENDING) {
            return new CanStartResult(task.id(), false, "task status is " + task.status().value(), dependencies(task, loaded.tasksById()), loaded.warnings());
        }
        List<TaskDependencyStatus> dependencies = dependencies(task, loaded.tasksById());
        List<String> blockers = dependencies.stream()
                .filter(dependency -> !dependency.completed())
                .map(TaskDependencyStatus::taskId)
                .toList();
        if (!blockers.isEmpty()) {
            return new CanStartResult(task.id(), false, "blocked by " + String.join(", ", blockers), dependencies, loaded.warnings());
        }
        return new CanStartResult(task.id(), true, "all dependencies completed", dependencies, loaded.warnings());
    }

    private List<TaskDependencyStatus> dependencies(TaskDefinition task, Map<String, TaskDefinition> tasksById) {
        List<TaskDependencyStatus> dependencies = new ArrayList<>();
        for (String dependencyId : task.blockedBy()) {
            TaskDefinition dependency = tasksById.get(dependencyId);
            if (dependency == null) {
                dependencies.add(new TaskDependencyStatus(dependencyId, false, "missing", false));
            } else {
                dependencies.add(new TaskDependencyStatus(dependencyId, true, dependency.status().value(), dependency.status() == TaskStatus.COMPLETED));
            }
        }
        return List.copyOf(dependencies);
    }

    private TaskDefinition findTask(String taskId, TaskLoadResult loaded) {
        String normalized = normalizeTaskId(taskId);
        TaskDefinition task = loaded.tasksById().get(normalized);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + normalized);
        }
        return task;
    }

    private String normalizeTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        String normalized = taskId.strip();
        validateTaskId(normalized);
        return normalized;
    }

    private void validateTaskId(String taskId) {
        if (!TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("task id must match task_000001 format: " + taskId);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText().strip();
    }

    private List<String> normalizeDependencies(List<String> dependencies) {
        if (dependencies == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String dependency : dependencies) {
            String taskId = normalizeTaskId(dependency);
            if (!normalized.contains(taskId)) {
                normalized.add(taskId);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return "main";
        }
        return owner.strip();
    }

    private long nextId(Map<String, TaskDefinition> tasksById) {
        long highWatermark = readHighWatermark();
        for (String id : tasksById.keySet()) {
            Matcher matcher = TASK_ID_PATTERN.matcher(id);
            if (matcher.matches()) {
                highWatermark = Math.max(highWatermark, Long.parseLong(matcher.group(1)));
            }
        }
        return highWatermark + 1;
    }

    private long readHighWatermark() {
        Path file = tasksDir().resolve(HIGH_WATERMARK_FILE_NAME);
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (text.isBlank()) {
                return 0;
            }
            long value = Long.parseLong(text);
            if (value < 0) {
                throw new IllegalStateException(".tasks/.highwatermark must be non-negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(".tasks/.highwatermark must contain an integer");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read .tasks/.highwatermark: " + e.getMessage(), e);
        }
    }

    private void writeHighWatermark(long value) {
        writeStringAtomically(tasksDir().resolve(HIGH_WATERMARK_FILE_NAME), Long.toString(value) + "\n");
    }

    private void writeTask(TaskDefinition task) {
        writeStringAtomically(tasksDir().resolve(task.id() + ".json"), toJson(task).toPrettyString() + "\n");
    }

    private void writeStringAtomically(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + relative(target) + ": " + e.getMessage(), e);
        }
    }

    private <T> T withLock(CheckedSupplier<T> action) {
        try {
            Files.createDirectories(tasksDir());
            Path lockFile = tasksDir().resolve(LOCK_FILE_NAME);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                channel.truncate(0);
                channel.write(ByteBuffer.wrap(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to lock .tasks: " + e.getMessage(), e);
        }
    }

    private String formatTaskId(long value) {
        return "task_%06d".formatted(value);
    }

    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(workspace)) {
            return workspace.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private List<TaskDefinition> sortedTasks(Map<String, TaskDefinition> tasksById) {
        return tasksById.values().stream()
                .sorted(Comparator.comparing(TaskDefinition::id))
                .toList();
    }

    public record TaskSummary(TaskDefinition task, boolean canStart) {
    }

    public record ListTasksResult(List<TaskSummary> tasks, List<String> warnings) {
        public ListTasksResult {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record TaskDetail(TaskDefinition task, CanStartResult canStart, List<String> warnings) {
        public TaskDetail {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record CompleteTaskResult(TaskDefinition task, List<TaskDefinition> unlockedTasks) {
        public CompleteTaskResult {
            unlockedTasks = unlockedTasks == null ? List.of() : List.copyOf(unlockedTasks);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws IOException;
    }
}
