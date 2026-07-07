package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelErrorCode;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelException;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.TraceEvent;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCliTest {

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

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
    void runReadonlyProfileHidesHighRiskTools() {
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

        int exitCode = commandLine.execute("run", "--profile", "readonly", "inspect");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(toolNames.get()).contains("read_file", "glob", "list_tasks", "get_task", "can_start");
        assertThat(toolNames.get()).doesNotContain("bash", "write_file", "edit_file", "subagent");
        assertThat(toolNames.get()).doesNotContain("create_task", "claim_task", "complete_task");
    }

    @Test
    void profileShowPrintsSanitizedEffectiveProfile() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of(
                "OPENAI_API_KEY", "secret-key",
                "OPENAI_MODEL", "env-model"
        ));
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("profile", "show", "readonly");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("\"profile\" : \"readonly\"");
        assertThat(out.toString()).contains("\"policy\" :");
        assertThat(out.toString()).contains("\"name\" : \"readonly\"");
        assertThat(out.toString()).contains("\"defaultAction\" : \"ask\"");
        assertThat(out.toString()).contains("\"contexts\" :");
        assertThat(out.toString()).contains("\"mcp\" : false");
        assertThat(out.toString()).doesNotContain("secret-key");
    }

    @Test
    void profileShowFailsForMissingCustomProfile() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(Map.of());
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("profile", "show", "missing_profile");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("Runtime profile not found");
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
    void runLoadsMcpToolsFromConfigAndRequiresApproval() throws IOException {
        Path config = writeMcpConfig("demo", Map.of());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                true,
                mcpEchoModel("from run")
        );
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--mcp-config", config.toString(), "--max-steps", "3", "use", "mcp");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("MCP tool calls execute in an external server process");
        assertThat(err.toString()).contains("server=demo, tool=echo");
        assertThat(out.toString()).contains("mcp echo: from run");
    }

    @Test
    void runNoMcpSkipsConfigLoading() {
        AtomicReference<List<String>> toolNames = new AtomicReference<>();
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> {
                    toolNames.set(tools.stream().map(Tool::name).toList());
                    return AgentStep.finalAnswer("no mcp");
                }
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("run", "--no-mcp", "--mcp-config", "../outside-mcp.json", "inspect");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("no mcp");
        assertThat(toolNames.get()).doesNotContain("mcp__demo__echo");
    }

    @Test
    void runRejectsMcpConfigOutsideWorkspace() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("unused")
        );
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--mcp-config", "../outside-mcp.json", "inspect");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString()).contains("MCP config path must stay within the workspace");
    }

    @Test
    void chatStartsMcpServerOnceAndReusesItAcrossClear() throws IOException {
        Path startFile = Path.of("target", "cli-mcp-tests", "starts-" + UUID.randomUUID() + ".txt");
        Path config = writeMcpConfig("demo", Map.of("MCP_FAKE_START_FILE", startFile.toString()));
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream("first\ny\n/clear\nsecond\ny\n/exit\n".getBytes(StandardCharsets.UTF_8)),
                true,
                mcpEchoModel(null)
        );
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("chat", "--no-trace", "--mcp-config", config.toString(), "--max-steps", "3");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("mcp echo: first");
        assertThat(out.toString()).contains("Session cleared.");
        assertThat(out.toString()).contains("mcp echo: second");
        assertThat(Files.readAllLines(startFile)).hasSize(1);
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
    void runColorAlwaysColorsTraceButKeepsFinalAnswerUnprefixed() {
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

        int exitCode = commandLine.execute("run", "--trace", "--color", "always", "hello");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("ok");
        assertThat(out.toString()).doesNotContain("assistant>");
        assertThat(out.toString()).doesNotContain("\u001B[");
        assertThat(err.toString()).contains("\u001B[2m[trace]\u001B[0m");
        assertThat(err.toString()).contains("\u001B[32mstatus=success\u001B[0m");
    }

    @Test
    void runColorNeverLeavesTracePlain() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                new ByteArrayInputStream(new byte[0]),
                false,
                (request, previousSteps, tools) -> AgentStep.finalAnswer("ok")
        );
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("run", "--trace", "--color", "never", "hello");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("[trace] model_call status=start phase=main");
        assertThat(err.toString()).doesNotContain("\u001B[");
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
        assertThat(out.toString()).contains("assistant> ok");
        assertThat(out.toString()).doesNotContain("[trace]");
        assertThat(err.toString()).contains("[trace] model_call status=start phase=main");
    }

    @Test
    void chatColorAlwaysColorsAssistantPrefixAndTrace() {
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

        int exitCode = commandLine.execute("chat", "--color", "always");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("\u001B[32massistant>\u001B[0m ok");
        assertThat(err.toString()).contains("\u001B[2m[trace]\u001B[0m");
    }

    @Test
    void chatColorAutoIsPlainForNonInteractiveInput() {
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

        int exitCode = commandLine.execute("chat", "--color", "auto");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("assistant> ok");
        assertThat(out.toString()).doesNotContain("\u001B[");
        assertThat(err.toString()).doesNotContain("\u001B[");
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
    void terminalStyleColorsTraceStatusesWithoutColoringPayloads() {
        TerminalStyle style = TerminalStyle.of("always", false);

        String success = style.trace(TraceEvent.of(TraceEvent.Type.TOOL_RESULT, Map.of(
                "success", "true",
                "summary", "plain payload"
        )));
        String failure = style.trace(TraceEvent.of(TraceEvent.Type.MODEL_CALL, Map.of(
                "status", "failed",
                "error", "TRANSIENT"
        )));
        String compression = style.trace(TraceEvent.of(TraceEvent.Type.COMPRESSION, Map.of(
                "stage", "MICRO_COMPACT"
        )));

        assertThat(success).contains("\u001B[32msuccess=true\u001B[0m");
        assertThat(success).contains("summary=\"plain payload\"");
        assertThat(success).doesNotContain("\u001B[32msummary");
        assertThat(failure).contains("\u001B[31mstatus=failed\u001B[0m");
        assertThat(compression).startsWith("\u001B[34m");
    }

    @Test
    void terminalStyleColorsTeamAndCronPrefixesOnly() {
        TerminalStyle style = TerminalStyle.of("always", false);

        assertThat(style.teamPrefix("[team alice]") + " done")
                .isEqualTo("\u001B[35m[team alice]\u001B[0m done");
        assertThat(style.cronPrefix("[cron job_1]") + " done")
                .isEqualTo("\u001B[34m[cron job_1]\u001B[0m done");
    }

    @Test
    void chatConsoleRoutesBackgroundApprovalWithoutEventLoopDispatch() throws Exception {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        StringWriter err = new StringWriter();
        AtomicReference<Boolean> approved = new AtomicReference<>();

        try (AgentCli.ChatConsole console = new AgentCli.ChatConsole(
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)),
                new PrintWriter(err),
                true
        )) {
            console.start();
            console.markEventLoopThread();
            console.printChatPrompt();
            Thread backgroundApproval = new Thread(() -> approved.set(console.approve(
                    permissionRequest(
                            "bash",
                            JsonNodeFactory.instance.objectNode().put("command", "ls"),
                            Map.of(
                                    "agent.permission.reviewer", "main",
                                    "agent.permission.review.reason", "Lead review accepted the teammate request"
                            )
                    ),
                    PermissionDecision.askUser("bash executes a workspace command", "command=ls")
            )));
            backgroundApproval.setDaemon(true);
            backgroundApproval.start();

            awaitText(err, "Tool approval required:");
            String approvalPrompt = err.toString();
            console.printChatPrompt();
            assertThat(err.toString()).isEqualTo(approvalPrompt);
            output.write("y\nhello after approval\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            backgroundApproval.join(2000);

            assertThat(approved.get()).isTrue();
            AgentCli.ChatConsole.Event nextUserEvent = console.nextEvent();
            assertThat(nextUserEvent.type()).isEqualTo(AgentCli.ChatConsole.EventType.USER_LINE);
            assertThat(nextUserEvent.line()).isEqualTo("hello after approval");
            assertThat(err.toString()).contains("chat> \nTool approval required:");
            assertThat(err.toString()).contains("reviewed by: main");
            assertThat(err.toString()).contains("review: Lead review accepted the teammate request");
        } finally {
            output.close();
        }
    }

    @Test
    void chatConsoleSeparatesAsyncOutputFromVisiblePrompt() {
        StringWriter err = new StringWriter();
        try (AgentCli.ChatConsole console = new AgentCli.ChatConsole(
                new BufferedReader(new StringReader("")),
                new PrintWriter(err),
                true
        )) {
            console.printChatPrompt();

            console.beforeAsyncOutput();
            console.beforeAsyncOutput();

            assertThat(err.toString()).isEqualTo("chat> \n");
        }
    }

    @Test
    void chatConsoleDoesNotRepaintPromptAfterTraceOutputWhileWaiting() throws Exception {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        StringWriter err = new StringWriter();
        AtomicReference<AgentCli.ChatConsole.Event> eventRef = new AtomicReference<>();

        try (AgentCli.ChatConsole console = new AgentCli.ChatConsole(
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)),
                new PrintWriter(err),
                true
        )) {
            console.start();
            console.printChatPrompt();
            Thread waiter = new Thread(() -> {
                try {
                    eventRef.set(console.nextEvent());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            awaitWaitingForInput(console);

            console.beforeAsyncOutput();
            err.append("[trace] model_call status=start\n");
            console.afterAsyncOutput(false);

            assertThat(err.toString()).contains("chat> \n[trace] model_call status=start\n");
            assertThat(err.toString()).doesNotContain("[trace] model_call status=start\nchat> ");

            output.write("hello\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            waiter.join(1000);

            assertThat(eventRef.get().line()).isEqualTo("hello");
        } finally {
            output.close();
        }
    }

    @Test
    void chatConsoleRepaintsPromptAfterAsyncOutputWhileWaiting() throws Exception {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        StringWriter err = new StringWriter();
        AtomicReference<AgentCli.ChatConsole.Event> eventRef = new AtomicReference<>();

        try (AgentCli.ChatConsole console = new AgentCli.ChatConsole(
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)),
                new PrintWriter(err),
                true
        )) {
            console.start();
            console.printChatPrompt();
            Thread waiter = new Thread(() -> {
                try {
                    eventRef.set(console.nextEvent());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            awaitWaitingForInput(console);

            console.beforeAsyncOutput();
            err.append("[team alice] done\n");
            console.afterAsyncOutput();
            output.write("hello\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            waiter.join(2000);

            assertThat(err.toString()).contains("chat> \n[team alice] done\nchat> ");
            assertThat(eventRef.get().line()).isEqualTo("hello");
        } finally {
            output.close();
        }
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
    void chatInjectsCompletedBackgroundTaskNotificationAfterClear() throws Exception {
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        StringWriter out = new StringWriter();
        CommandLine commandLine = AgentCli.newCommandLine(
                Map.of(),
                input,
                true,
                backgroundBashChatModel()
        );
        commandLine.setOut(new PrintWriter(out));

        AtomicReference<Integer> exitCode = new AtomicReference<>();
        Thread cliThread = new Thread(() -> exitCode.set(commandLine.execute("chat", "--max-steps", "3")));
        cliThread.setDaemon(true);
        cliThread.start();

        output.write("start\ny\n/clear\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
        awaitText(out, "Session cleared.");
        Thread.sleep(250);
        output.write("second\n".getBytes(StandardCharsets.UTF_8));
        output.close();
        cliThread.join(3000);

        assertThat(exitCode.get()).isZero();
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

    private static Path writeMcpConfig(String serverName, Map<String, String> env) throws IOException {
        Path dir = Path.of("target", "cli-mcp-tests", UUID.randomUUID().toString());
        Files.createDirectories(dir);
        Path config = dir.resolve("mcp.json");
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(serverName);
        server.put("command", javaBinary());
        server.putArray("args")
                .add("-cp")
                .add(System.getProperty("java.class.path"))
                .add(FakeMcpServer.class.getName());
        ObjectNode envNode = server.putObject("env");
        env.forEach(envNode::put);
        Files.writeString(config, TEST_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        return config;
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static ModelClient mcpEchoModel(String fixedText) {
        AtomicInteger calls = new AtomicInteger();
        return (request, previousSteps, tools) -> {
            if (previousSteps.isEmpty()) {
                ObjectNode arguments = JsonNodeFactory.instance.objectNode();
                arguments.put("text", fixedText == null ? request.task() : fixedText);
                return AgentStep.toolCall("call-mcp-" + calls.incrementAndGet(), "mcp__demo__echo", arguments);
            }
            return AgentStep.finalAnswer(previousSteps.get(previousSteps.size() - 1).content());
        };
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

    private static PermissionRequest permissionRequest(String toolName, ObjectNode arguments) {
        return permissionRequest(toolName, arguments, Map.of());
    }

    private static PermissionRequest permissionRequest(String toolName, ObjectNode arguments, Map<String, String> metadata) {
        return new PermissionRequest("call-test", toolName, arguments, metadata);
    }

    private static void awaitText(StringWriter writer, String text) throws InterruptedException {
        for (int i = 0; i < 50 && !writer.toString().contains(text); i++) {
            Thread.sleep(20);
        }
    }

    private static void awaitWaitingForInput(AgentCli.ChatConsole console) throws InterruptedException {
        for (int i = 0; i < 50 && !console.isWaitingForInput(); i++) {
            Thread.sleep(20);
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
