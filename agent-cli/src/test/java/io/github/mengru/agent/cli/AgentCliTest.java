package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCliTest {

    @TempDir
    Path workspace;

    @Test
    void runsTaskFromCommandLine() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "hello", "cli");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Echo agent completed: hello cli");
    }

    @Test
    void runsTaskFromStandardInput() {
        InputStream originalIn = System.in;
        StringWriter out = new StringWriter();
        try {
            System.setIn(new ByteArrayInputStream("hello stdin\n".getBytes(StandardCharsets.UTF_8)));
            CommandLine commandLine = AgentCli.newCommandLine(Map.of());
            commandLine.setOut(new PrintWriter(out));

            int exitCode = commandLine.execute("run");

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("Echo agent completed: hello stdin");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void failsWhenTaskIsMissing() {
        InputStream originalIn = System.in;
        StringWriter err = new StringWriter();
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            CommandLine commandLine = AgentCli.newCommandLine(Map.of());
            commandLine.setErr(new PrintWriter(err));

            int exitCode = commandLine.execute("run");

            assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
            assertThat(err.toString()).contains("Missing task");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void openAiCompatibleProviderRequiresModel() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of("OPENAI_API_KEY", "test-key"));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--provider", "openai-compatible", "hello");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("--model or OPENAI_MODEL is required");
    }

    @Test
    void openAiCompatibleProviderRequiresApiKey() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of());
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--provider", "openai-compatible", "--model", "test-model", "hello");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("OPENAI_API_KEY");
    }

    @Test
    void openAiCompatibleProviderUsesOpenAiModelEnvironmentFallback() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of(
                "OPENAI_API_KEY", "test-key",
                "OPENAI_MODEL", "qwen3-coder-plus",
                "OPENAI_BASE_URL", "http://127.0.0.1:9/v1"
        ));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--provider", "openai-compatible", "hello");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        assertThat(err.toString()).contains("OpenAI-compatible request failed");
        assertThat(err.toString()).doesNotContain("--model or OPENAI_MODEL is required");
    }

    @Test
    void commandLineModelOverridesOpenAiModelEnvironmentFallback() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of(
                "OPENAI_API_KEY", "test-key",
                "OPENAI_MODEL", "qwen3-coder-plus",
                "OPENAI_BASE_URL", "http://127.0.0.1:9/v1"
        ));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(
                "run",
                "--provider", "openai-compatible",
                "--model", "gpt-test",
                "hello"
        );

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        assertThat(err.toString()).contains("OpenAI-compatible request failed");
        assertThat(err.toString()).doesNotContain("--model or OPENAI_MODEL is required");
    }

    @Test
    void cliApprovalAllowsToolExecution() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                true,
                writeFileModel("target/cli-permission-allow.txt")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "write", "with", "approval");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("Tool approval required");
        assertThat(out.toString()).contains("wrote");
        assertThat(out.toString()).contains("target/cli-permission-allow.txt");
    }

    @Test
    void cliApprovalDeniesToolExecution() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                true,
                writeFileModel("target/cli-permission-deny.txt")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "write", "with", "denial");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("Allow this tool call?");
        assertThat(out.toString()).contains("permission denied");
        assertThat(out.toString()).contains("user rejected approval");
    }

    @Test
    void cliNonInteractiveApprovalDefaultsToDeny() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                writeFileModel("target/cli-permission-noninteractive.txt")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "write", "noninteractive");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).doesNotContain("Allow this tool call?");
        assertThat(out.toString()).contains("permission denied");
    }

    @Test
    void cliDefaultRuntimeToolsExposeSubagent() {
        AtomicReference<java.util.List<String>> toolNames = new AtomicReference<>();
        ModelClient model = (request, previousSteps, tools) -> {
            toolNames.set(tools.stream().map(Tool::name).toList());
            return AgentStep.finalAnswer("ok");
        };
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                model
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "inspect");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(toolNames.get()).contains("subagent");
    }

    @Test
    void runAcceptsContextCompressionOptions() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(
                "run",
                "--no-context-compression",
                "--context-window-tokens", "100",
                "--max-output-tokens", "20",
                "--reserved-tokens", "20",
                "hello"
        );

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
    }

    @Test
    void runRejectsInvalidContextCompressionBudget() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(
                "run",
                "--context-window-tokens", "100",
                "--max-output-tokens", "80",
                "--reserved-tokens", "30",
                "hello"
        );

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("contextWindowTokens");
    }

    @Test
    void chatKeepsSuccessfulConversationHistoryAcrossInputLines() {
        ModelClient model = (request, previousSteps, tools) ->
                AgentStep.finalAnswer("history=" + request.conversationHistory().size() + ", task=" + request.task());
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("first\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                false,
                model
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("history=0, task=first");
        assertThat(out.toString()).contains("history=2, task=second");
    }

    @Test
    void chatClearRemovesConversationHistory() {
        ModelClient model = (request, previousSteps, tools) ->
                AgentStep.finalAnswer("history=" + request.conversationHistory().size() + ", task=" + request.task());
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("first\n/clear\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                false,
                model
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("history=0, task=first");
        assertThat(out.toString()).contains("Session cleared.");
        assertThat(out.toString()).contains("history=0, task=second");
    }

    @Test
    void chatExitAndQuitReturnZero() {
        CommandLine exitCommand = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("/exit\n".getBytes(StandardCharsets.UTF_8)),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("should not run")
        );
        CommandLine quitCommand = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("/quit\n".getBytes(StandardCharsets.UTF_8)),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("should not run")
        );

        assertThat(exitCommand.execute("chat")).isZero();
        assertThat(quitCommand.execute("chat")).isZero();
    }

    @Test
    void chatDoesNotStoreIncompleteResults() {
        ModelClient model = (request, previousSteps, tools) -> {
            if ("first".equals(request.task())) {
                return AgentStep.thought("not done");
            }
            return AgentStep.finalAnswer("history=" + request.conversationHistory().size());
        };
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("first\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                false,
                model
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat", "--max-steps", "1");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("not done");
        assertThat(out.toString()).contains("history=0");
    }

    @Test
    void memoryListShowsIndex() throws IOException {
        writeMemory("style.md", "tab-style", "Use tab indentation", "user", "Use tabs instead of spaces.");
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new AgentCli.MemoryListCommand(workspace));
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("tab-style");
        assertThat(out.toString()).contains("Use tab indentation");
    }

    @Test
    void memoryShowPrintsMemoryContent() throws IOException {
        writeMemory("style.md", "tab-style", "Use tab indentation", "user", "Use tabs instead of spaces.");
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new AgentCli.MemoryShowCommand(workspace));
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("tab-style");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Use tabs instead of spaces.");
    }

    private static ModelClient writeFileModel(String path) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", path);
        arguments.put("content", "hello from cli approval test");
        return (request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-cli-write", "write_file", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        };
    }

    private void writeMemory(String fileName, String name, String description, String type, String body) throws IOException {
        Path memoryDir = workspace.resolve(".memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(fileName), """
                ---
                name: %s
                description: %s
                type: %s
                ---
                
                %s
                """.formatted(name, description, type, body), StandardCharsets.UTF_8);
    }
}
