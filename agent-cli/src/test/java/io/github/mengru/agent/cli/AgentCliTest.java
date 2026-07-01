package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentStep;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.Tool;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCliTest {

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
}
