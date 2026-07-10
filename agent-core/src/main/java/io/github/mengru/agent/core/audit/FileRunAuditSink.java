package io.github.mengru.agent.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mengru.agent.api.AuditEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class FileRunAuditSink implements RunAuditSink {

    public static final String RUNS_DIR = ".runs";
    public static final String INDEX_FILE = "RUNS.jsonl";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path runsDir;

    public FileRunAuditSink(Path workspaceRoot) {
        Path workspace = workspaceRoot == null ? Path.of("") : workspaceRoot;
        this.runsDir = workspace.resolve(RUNS_DIR).toAbsolutePath().normalize();
    }

    public Path runsDir() {
        return runsDir;
    }

    @Override
    public synchronized void emit(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        try {
            Files.createDirectories(runsDir);
            Files.writeString(
                    runFile(event.runId()),
                    JSON.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write run audit event", e);
        }
    }

    @Override
    public synchronized void closeRun(RunAuditSummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        try {
            Files.createDirectories(runsDir);
            Files.writeString(
                    runsDir.resolve(INDEX_FILE),
                    JSON.writeValueAsString(summary) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write run audit index", e);
        }
    }

    private Path runFile(String runId) {
        return runsDir.resolve(safeRunId(runId) + ".jsonl");
    }

    private static String safeRunId(String runId) {
        String value = runId == null ? "" : runId;
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("runId must match [A-Za-z0-9_-]+: " + value);
        }
        return value;
    }
}
