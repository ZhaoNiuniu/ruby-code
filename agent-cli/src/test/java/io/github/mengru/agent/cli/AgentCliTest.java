package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelErrorCode;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelException;
import io.github.mengru.agent.api.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

        int exitCode = commandLine.execute("run", "--provider", "openai-compatible", "--no-error-recovery", "hello");

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
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of(
                "OPENAI_API_KEY", "test-key",
                "OPENAI_MODEL", "qwen3-coder-plus",
                "OPENAI_BASE_URL", "http://127.0.0.1:9/v1"
        ));
        commandLine.setErr(new PrintWriter(err));
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "--provider", "openai-compatible", "hello");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("OpenAI-compatible request failed");
        assertThat(err.toString()).doesNotContain("--model or OPENAI_MODEL is required");
    }

    @Test
    void commandLineModelOverridesOpenAiModelEnvironmentFallback() {
        StringWriter err = new StringWriter();
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of(
                "OPENAI_API_KEY", "test-key",
                "OPENAI_MODEL", "qwen3-coder-plus",
                "OPENAI_BASE_URL", "http://127.0.0.1:9/v1"
        ));
        commandLine.setErr(new PrintWriter(err));
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(
                "run",
                "--provider", "openai-compatible",
                "--model", "gpt-test",
                "--no-error-recovery",
                "hello"
        );

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("OpenAI-compatible request failed");
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
        assertThat(toolNames.get()).contains("create_task", "list_tasks", "get_task", "can_start", "claim_task", "complete_task");
        assertThat(toolNames.get()).doesNotContain("schedule_task");
        assertThat(toolNames.get()).doesNotContain("spawn_teammate", "send_message", "list_teammates");
    }

    @Test
    void runPassesAgentNameMetadata() {
        AtomicReference<String> seenAgentName = new AtomicReference<>();
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> {
                    seenAgentName.set(request.metadata().get("agent.name"));
                    return AgentStep.finalAnswer("ok");
                }
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "--agent-name", "builder-a", "inspect");

        assertThat(exitCode).isZero();
        assertThat(seenAgentName.get()).isEqualTo("builder-a");
    }

    @Test
    void chatRuntimeToolsExposeSchedulerTools() {
        AtomicReference<java.util.List<String>> toolNames = new AtomicReference<>();
        ModelClient model = (request, previousSteps, tools) -> {
            toolNames.set(tools.stream().map(Tool::name).toList());
            return AgentStep.finalAnswer("ok");
        };
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("inspect\n".getBytes(StandardCharsets.UTF_8)),
                false,
                model
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat", "--no-trace");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(toolNames.get()).contains("schedule_task", "list_scheduled_tasks", "cancel_scheduled_task");
        assertThat(toolNames.get()).contains("spawn_teammate", "send_message", "list_teammates");
        assertThat(toolNames.get()).contains("create_task", "list_tasks", "get_task", "can_start", "claim_task", "complete_task");
    }

    @Test
    void runInjectsRuntimeModelIdentityIntoSystemPrompt() {
        AtomicReference<String> seenSystemPrompt = new AtomicReference<>();
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> {
                    seenSystemPrompt.set(request.systemPrompt());
                    return AgentStep.finalAnswer("ok");
                }
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "who", "are", "you");

        assertThat(exitCode).isZero();
        assertThat(seenSystemPrompt.get()).contains("## model_identity");
        assertThat(seenSystemPrompt.get()).contains("provider: fixed-test-client");
        assertThat(seenSystemPrompt.get()).contains("model: fixed-test-client");
        assertThat(seenSystemPrompt.get()).contains("Do not claim to be Claude");
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
    void runPassesGenerationMaxOutputTokensToRequest() {
        AtomicReference<Integer> seenMaxOutputTokens = new AtomicReference<>();
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> {
                    seenMaxOutputTokens.set(request.modelOptions().maxOutputTokens());
                    return AgentStep.finalAnswer("ok");
                }
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute(
                "run",
                "--generation-max-output-tokens", "12345",
                "hello"
        );

        assertThat(exitCode).isZero();
        assertThat(seenMaxOutputTokens.get()).isEqualTo(12345);
    }

    @Test
    void runDoesNotPrintTraceByDefault() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "hello");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(err.toString()).doesNotContain("[trace]");
    }

    @Test
    void runTracePrintsToStderrAndLeavesStdoutForFinalAnswer() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--trace", "hello");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(out.toString()).doesNotContain("[trace]");
        assertThat(err.toString()).contains("[trace] model_call status=start phase=main");
        assertThat(err.toString()).contains("[trace] final_answer completed=true");
    }

    @Test
    void runCanDisableErrorRecovery() {
        AtomicInteger calls = new AtomicInteger();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> {
                    calls.incrementAndGet();
                    throw new ModelException(ModelErrorCode.TRANSIENT, "rate limited");
                }
        );

        int exitCode = commandLine.execute(
                "run",
                "--no-error-recovery",
                "hello"
        );

        assertThat(exitCode).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(1);
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
    void chatPrintsTraceByDefault() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("hello\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("chat");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(out.toString()).doesNotContain("[trace]");
        assertThat(err.toString()).contains("[trace] model_call status=start phase=main");
    }

    @Test
    void chatNoTraceDisablesRealtimeTrace() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("hello\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("chat", "--no-trace");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(err.toString()).doesNotContain("[trace]");
    }

    @Test
    void chatRunsDueCronJobAndPrintsPrefixedResult() throws IOException {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        Thread writer = new Thread(() -> {
            try {
                output.write("schedule\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                sleepQuietly(2500);
                output.write("/exit\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.setDaemon(true);
        writer.start();

        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                input,
                false,
                cronSchedulingModel()
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat", "--no-trace", "--max-steps", "3");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("scheduled");
        assertThat(out.toString()).contains("[cron ");
        assertThat(out.toString()).contains("past-once");
        assertThat(out.toString()).contains("cron history=2");
        assertThat(out.toString()).contains("permission denied");
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
    void chatInjectsCompletedBackgroundTaskNotificationAfterClear() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("start\ny\n/clear\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                true,
                backgroundBashChatModel()
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat", "--max-steps", "3");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("background task started");
        assertThat(out.toString()).contains("Session cleared.");
        assertThat(out.toString()).contains("notifications=1");
    }

    @Test
    void runDowngradesRequestedBackgroundBashToForeground() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                true,
                backgroundBashRunModel()
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "--max-steps", "3", "start");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("exitCode: 0");
        assertThat(out.toString()).contains("foreground");
        assertThat(out.toString()).doesNotContain("bg_id");
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

    private static ModelClient backgroundBashChatModel() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("command", "printf cli-bg");
        arguments.put("run_in_background", true);
        AtomicInteger calls = new AtomicInteger();
        return (request, previousSteps, tools) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return AgentStep.toolCall("call-cli-bg", "bash", arguments);
            }
            if (call == 2) {
                sleepQuietly(100);
                return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
            }
            return AgentStep.finalAnswer("notifications=" + request.notifications().size());
        };
    }

    private static ModelClient backgroundBashRunModel() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("command", "printf foreground");
        arguments.put("run_in_background", true);
        return (request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-cli-run-bg", "bash", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        };
    }

    private static ModelClient cronSchedulingModel() {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("type", "once");
        arguments.put("name", "past-once");
        arguments.put("task", "report cron history");
        arguments.put("runAt", "2000-01-01T00:00:00Z");
        arguments.put("durable", false);
        ObjectNode bashArguments = JsonNodeFactory.instance.objectNode();
        bashArguments.put("command", "printf cron");
        return (request, previousSteps, tools) -> {
            if (request.task().contains("<cron_trigger")) {
                if (previousSteps.isEmpty()) {
                    return AgentStep.toolCall("call-cron-bash", "bash", bashArguments);
                }
                return AgentStep.finalAnswer("cron history="
                        + request.conversationHistory().size()
                        + " "
                        + previousSteps.get(previousSteps.size() - 1).content());
            }
            if (previousSteps.isEmpty()) {
                return AgentStep.toolCall("call-schedule", "schedule_task", arguments);
            }
            return AgentStep.finalAnswer("scheduled");
        };
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
