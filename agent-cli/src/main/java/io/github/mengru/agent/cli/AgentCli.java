package io.github.mengru.agent.cli;

import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.core.AgentSession;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.EchoModelClient;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.provider.openai.OpenAiCompatibleException;
import io.github.mengru.agent.provider.openai.OpenAiCompatibleModelClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        version = "agent 0.1.0-SNAPSHOT",
        description = "Runs the local Java agent scaffold."
)
public final class AgentCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    public AgentCli() {
        this(System.getenv());
    }

    AgentCli(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
    }

    public static void main(String[] args) {
        int exitCode = newCommandLine().execute(args);
        System.exit(exitCode);
    }

    static CommandLine newCommandLine() {
        return newCommandLine(System.getenv());
    }

    static CommandLine newCommandLine(Map<String, String> environment) {
        return newCommandLine(environment, System.in, System.console() != null);
    }

    static CommandLine newCommandLine(Map<String, String> environment, InputStream inputStream, boolean interactiveApproval) {
        CommandLine commandLine = new CommandLine(new AgentCli(environment));
        commandLine.addSubcommand("run", new RunCommand(environment, inputStream, interactiveApproval));
        commandLine.addSubcommand("chat", new ChatCommand(environment, inputStream, interactiveApproval));
        return commandLine;
    }

    static CommandLine newCommandLine(
            Map<String, String> environment,
            InputStream inputStream,
            boolean interactiveApproval,
            ModelClient fixedModelClient
    ) {
        CommandLine commandLine = new CommandLine(new AgentCli(environment));
        commandLine.addSubcommand("run", new RunCommand(environment, inputStream, interactiveApproval, fixedModelClient));
        commandLine.addSubcommand("chat", new ChatCommand(environment, inputStream, interactiveApproval, fixedModelClient));
        return commandLine;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    @Command(name = "run", description = "Runs a task through the default echo agent.")
    static final class RunCommand implements Callable<Integer> {

        private final Map<String, String> environment;
        private final BufferedReader inputReader;
        private final boolean interactiveApproval;
        private final ModelClient fixedModelClient;

        RunCommand() {
            this(System.getenv());
        }

        RunCommand(Map<String, String> environment) {
            this(environment, System.in, System.console() != null);
        }

        RunCommand(Map<String, String> environment, InputStream inputStream, boolean interactiveApproval) {
            this(environment, inputStream, interactiveApproval, null);
        }

        RunCommand(Map<String, String> environment, InputStream inputStream, boolean interactiveApproval, ModelClient fixedModelClient) {
            this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
            this.inputReader = new BufferedReader(new InputStreamReader(
                    Objects.requireNonNull(inputStream, "inputStream must not be null"),
                    StandardCharsets.UTF_8
            ));
            this.interactiveApproval = interactiveApproval;
            this.fixedModelClient = fixedModelClient;
        }

        @Spec
        private CommandSpec spec;

        @Option(names = "--max-steps", defaultValue = "8", description = "Maximum agent loop steps.")
        private int maxSteps;

        @Option(names = "--provider", defaultValue = "echo", description = "Provider to use: echo, openai-compatible.")
        private String provider;

        @Option(names = "--model", description = "Model name for --provider openai-compatible. Defaults to OPENAI_MODEL.")
        private String model;

        @Option(names = "--system", description = "System prompt for the agent.")
        private String systemPrompt;

        @Parameters(arity = "0..*", paramLabel = "TASK", description = "Task for the agent to run.")
        private List<String> taskParts;

        @Override
        public Integer call() throws IOException {
            String task = resolveTask();
            if (task.isBlank()) {
                spec.commandLine().getErr().println("Missing task. Pass TASK arguments, type one at the prompt, or pipe one through stdin.");
                return CommandLine.ExitCode.USAGE;
            }

            DefaultAgent agent;
            try {
                ModelClient modelClient = createModelClient();
                UserApprover userApprover = createUserApprover();
                SkillCatalog skillCatalog = SkillCatalog.scanDefault();
                ToolRegistry toolRegistry = ToolRegistry.defaultToolsWithSubagent(modelClient, userApprover, skillCatalog);
                agent = new DefaultAgent(
                        modelClient,
                        toolRegistry,
                        HookRegistry.defaultsFor(toolRegistry, userApprover)
                );
            } catch (IllegalArgumentException | OpenAiCompatibleException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.USAGE;
            }

            AgentRequest request = new AgentRequest(task, maxSteps, Map.of(), systemPrompt);
            AgentResult result;
            try {
                result = agent.run(request);
            } catch (OpenAiCompatibleException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.SOFTWARE;
            }

            spec.commandLine().getOut().println(result.output());
            return result.completed() ? 0 : 2;
        }

        private UserApprover createUserApprover() {
            return (request, decision) -> {
                if (!interactiveApproval) {
                    return false;
                }
                spec.commandLine().getErr().println("Tool approval required:");
                spec.commandLine().getErr().println("  tool: " + request.toolName());
                spec.commandLine().getErr().println("  reason: " + decision.reason());
                if (!decision.riskSummary().isBlank()) {
                    spec.commandLine().getErr().println("  risk: " + decision.riskSummary());
                }
                spec.commandLine().getErr().println("  arguments: " + summarizeArguments(request));
                spec.commandLine().getErr().print("Allow this tool call? [y/N] ");
                spec.commandLine().getErr().flush();
                try {
                    String line = inputReader.readLine();
                    return line != null && ("y".equalsIgnoreCase(line.trim()) || "yes".equalsIgnoreCase(line.trim()));
                } catch (IOException e) {
                    return false;
                }
            };
        }

        private static String summarizeArguments(PermissionRequest request) {
            String value = request.arguments().toString();
            if (value.length() <= 500) {
                return value;
            }
            return value.substring(0, 500) + "...[truncated]";
        }

        private ModelClient createModelClient() {
            if (fixedModelClient != null) {
                return fixedModelClient;
            }
            return switch (provider) {
                case "echo" -> new EchoModelClient();
                case "openai-compatible" -> {
                    String resolvedModel = resolveModel();
                    if (resolvedModel == null) {
                        throw new IllegalArgumentException("--model or OPENAI_MODEL is required when --provider openai-compatible.");
                    }
                    yield OpenAiCompatibleModelClient.fromEnvironment(resolvedModel, environment);
                }
                default -> throw new IllegalArgumentException("Unknown provider: " + provider);
            };
        }

        private String resolveModel() {
            if (model != null && !model.isBlank()) {
                return model.strip();
            }
            String environmentModel = environment.get("OPENAI_MODEL");
            if (environmentModel != null && !environmentModel.isBlank()) {
                return environmentModel.strip();
            }
            return null;
        }

        private String resolveTask() throws IOException {
            if (taskParts != null && !taskParts.isEmpty()) {
                return String.join(" ", taskParts);
            }

            Console console = System.console();
            if (console != null) {
                String line = console.readLine("Task> ");
                return line == null ? "" : line.trim();
            }

            String line = inputReader.readLine();
            return line == null ? "" : line.trim();
        }
    }

    @Command(name = "chat", description = "Runs an in-process multi-turn agent session.")
    static final class ChatCommand implements Callable<Integer> {

        private final Map<String, String> environment;
        private final BufferedReader inputReader;
        private final boolean interactiveApproval;
        private final ModelClient fixedModelClient;

        ChatCommand() {
            this(System.getenv());
        }

        ChatCommand(Map<String, String> environment) {
            this(environment, System.in, System.console() != null);
        }

        ChatCommand(Map<String, String> environment, InputStream inputStream, boolean interactiveApproval) {
            this(environment, inputStream, interactiveApproval, null);
        }

        ChatCommand(Map<String, String> environment, InputStream inputStream, boolean interactiveApproval, ModelClient fixedModelClient) {
            this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
            this.inputReader = new BufferedReader(new InputStreamReader(
                    Objects.requireNonNull(inputStream, "inputStream must not be null"),
                    StandardCharsets.UTF_8
            ));
            this.interactiveApproval = interactiveApproval;
            this.fixedModelClient = fixedModelClient;
        }

        @Spec
        private CommandSpec spec;

        @Option(names = "--max-steps", defaultValue = "8", description = "Maximum agent loop steps per turn.")
        private int maxSteps;

        @Option(names = "--provider", defaultValue = "echo", description = "Provider to use: echo, openai-compatible.")
        private String provider;

        @Option(names = "--model", description = "Model name for --provider openai-compatible. Defaults to OPENAI_MODEL.")
        private String model;

        @Option(names = "--system", description = "System prompt for the agent.")
        private String systemPrompt;

        @Override
        public Integer call() throws IOException {
            AgentSession session;
            try {
                ModelClient modelClient = createModelClient();
                UserApprover userApprover = createUserApprover();
                SkillCatalog skillCatalog = SkillCatalog.scanDefault();
                ToolRegistry toolRegistry = ToolRegistry.defaultToolsWithSubagent(modelClient, userApprover, skillCatalog);
                session = new AgentSession(new DefaultAgent(
                        modelClient,
                        toolRegistry,
                        HookRegistry.defaultsFor(toolRegistry, userApprover)
                ));
            } catch (IllegalArgumentException | OpenAiCompatibleException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.USAGE;
            }

            int lastExitCode = 0;
            while (true) {
                if (interactiveApproval) {
                    spec.commandLine().getErr().print("chat> ");
                    spec.commandLine().getErr().flush();
                }
                String line = inputReader.readLine();
                if (line == null) {
                    return lastExitCode;
                }
                String task = line.strip();
                if (task.isBlank()) {
                    continue;
                }
                if ("/exit".equals(task) || "/quit".equals(task)) {
                    return 0;
                }
                if ("/clear".equals(task)) {
                    session.clear();
                    spec.commandLine().getOut().println("Session cleared.");
                    lastExitCode = 0;
                    continue;
                }

                AgentResult result;
                try {
                    result = session.run(new AgentRequest(task, maxSteps, Map.of(), systemPrompt));
                } catch (OpenAiCompatibleException e) {
                    spec.commandLine().getErr().println(e.getMessage());
                    lastExitCode = CommandLine.ExitCode.SOFTWARE;
                    continue;
                }
                spec.commandLine().getOut().println(result.output());
                lastExitCode = result.completed() ? 0 : 2;
            }
        }

        private UserApprover createUserApprover() {
            return (request, decision) -> {
                if (!interactiveApproval) {
                    return false;
                }
                spec.commandLine().getErr().println("Tool approval required:");
                spec.commandLine().getErr().println("  tool: " + request.toolName());
                spec.commandLine().getErr().println("  reason: " + decision.reason());
                if (!decision.riskSummary().isBlank()) {
                    spec.commandLine().getErr().println("  risk: " + decision.riskSummary());
                }
                spec.commandLine().getErr().println("  arguments: " + summarizeArguments(request));
                spec.commandLine().getErr().print("Allow this tool call? [y/N] ");
                spec.commandLine().getErr().flush();
                try {
                    String line = inputReader.readLine();
                    return line != null && ("y".equalsIgnoreCase(line.trim()) || "yes".equalsIgnoreCase(line.trim()));
                } catch (IOException e) {
                    return false;
                }
            };
        }

        private static String summarizeArguments(PermissionRequest request) {
            String value = request.arguments().toString();
            if (value.length() <= 500) {
                return value;
            }
            return value.substring(0, 500) + "...[truncated]";
        }

        private ModelClient createModelClient() {
            if (fixedModelClient != null) {
                return fixedModelClient;
            }
            return switch (provider) {
                case "echo" -> new EchoModelClient();
                case "openai-compatible" -> {
                    String resolvedModel = resolveModel();
                    if (resolvedModel == null) {
                        throw new IllegalArgumentException("--model or OPENAI_MODEL is required when --provider openai-compatible.");
                    }
                    yield OpenAiCompatibleModelClient.fromEnvironment(resolvedModel, environment);
                }
                default -> throw new IllegalArgumentException("Unknown provider: " + provider);
            };
        }

        private String resolveModel() {
            if (model != null && !model.isBlank()) {
                return model.strip();
            }
            String environmentModel = environment.get("OPENAI_MODEL");
            if (environmentModel != null && !environmentModel.isBlank()) {
                return environmentModel.strip();
            }
            return null;
        }
    }
}
