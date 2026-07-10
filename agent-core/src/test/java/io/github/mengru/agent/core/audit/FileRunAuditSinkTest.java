package io.github.mengru.agent.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mengru.agent.api.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileRunAuditSinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void writesRunJsonlAndIndex() throws Exception {
        FileRunAuditSink sink = new FileRunAuditSink(workspace);
        AuditEvent event = AuditEvent.of(
                "run_test",
                1,
                AuditEvent.Type.TOOL_CALL,
                "lead:main",
                "main",
                "",
                "msg_1",
                Map.of("tool", "read_file")
        );

        sink.emit(event);
        sink.closeRun(new RunAuditSummary(
                "run_test",
                "",
                "lead:main",
                "user",
                "2026-07-07T00:00:00Z",
                "2026-07-07T00:00:01Z",
                "completed",
                "ok"
        ));

        Path runFile = workspace.resolve(".runs").resolve("run_test.jsonl");
        Path indexFile = workspace.resolve(".runs").resolve("RUNS.jsonl");
        assertThat(runFile).exists();
        assertThat(indexFile).exists();

        AuditEvent parsedEvent = JSON.readValue(Files.readString(runFile).strip(), AuditEvent.class);
        assertThat(parsedEvent.runId()).isEqualTo("run_test");
        assertThat(parsedEvent.attributes()).containsEntry("tool", "read_file");

        RunAuditSummary parsedSummary = JSON.readValue(Files.readString(indexFile).strip(), RunAuditSummary.class);
        assertThat(parsedSummary.status()).isEqualTo("completed");
        assertThat(parsedSummary.summary()).isEqualTo("ok");
    }
}
