package io.github.mengru.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelOptions;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.core.AgentSession;
import io.github.mengru.agent.core.background.BackgroundTaskManager;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.EchoModelClient;
import io.github.mengru.agent.core.context.ContextCompressionConfig;
import io.github.mengru.agent.core.context.ContextManager;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.memory.MemoryDefinition;
import io.github.mengru.agent.core.memory.MemoryExtractor;
import io.github.mengru.agent.core.memory.MemoryStore;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.permission.PermissionChecker;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.prompt.PromptAssembler;
import io.github.mengru.agent.core.recovery.ErrorRecoveryConfig;
import io.github.mengru.agent.core.scheduler.CronExecutionResult;
import io.github.mengru.agent.core.scheduler.CronQueue;
import io.github.mengru.agent.core.scheduler.CronQueueProcessor;
import io.github.mengru.agent.core.scheduler.CronScheduler;
import io.github.mengru.agent.core.scheduler.CronTriggeredTask;
import io.github.mengru.agent.core.scheduler.ScheduledJobStore;
import io.github.mengru.agent.core.scheduler.ScheduledTaskManager;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.team.TeamExecutionResult;
import io.github.mengru.agent.core.team.TeamInboxPoller;
import io.github.mengru.agent.core.team.TeamMessage;
import io.github.mengru.agent.core.team.TeamRuntime;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.trace.TraceSink;
import io.github.mengru.agent.mcp.McpConfig;
import io.github.mengru.agent.mcp.McpException;
import io.github.mengru.agent.mcp.McpToolProvider;
import io.github.mengru.agent.provider.openai.OpenAiCompatibleException;
import io.github.mengru.agent.provider.openai.OpenAiCompatibleModelClient;
import io.github.mengru.agent.runtime.PolicyPermissionChecker;
import io.github.mengru.agent.runtime.RuntimeProfileException;
import io.github.mengru.agent.runtime.RuntimeProfileOverrides;
import io.github.mengru.agent.runtime.RuntimeProfileResolution;
import io.github.mengru.agent.runtime.RuntimeProfileResolver;
import io.github.mengru.agent.runtime.RuntimeSettings;
import io.github.mengru.agent.runtime.RuntimeToolFilter;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

@Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        version = "agent 0.1.0-SNAPSHOT",
        description = "Runs the local Java agent scaffold."
)
public final class AgentCli implements Callable<Integer> {

    private static final Path WORKSPACE_ROOT = Path.of("");
    private static final ObjectMapper JSON = new ObjectMapper();

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
        commandLine.addSubcommand("memory", new MemoryCommand());
        CommandLine profile = new CommandLine(new ProfileCommand());
        profile.addSubcommand("show", new ProfileShowCommand(environment));
        commandLine.addSubcommand("profile", profile);
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
        commandLine.addSubcommand("memory", new MemoryCommand());
        CommandLine profile = new CommandLine(new ProfileCommand());
        profile.addSubcommand("show", new ProfileShowCommand(environment));
        commandLine.addSubcommand("profile", profile);
        return commandLine;
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }

    private static MemoryCatalog prepareMemoryCatalog(MemoryStore memoryStore) {
        MemoryCatalog memoryCatalog = memoryStore.catalog();
        if (!memoryCatalog.isEmpty()
                || !memoryCatalog.warnings().isEmpty()
                || Files.exists(memoryCatalog.memoryDir())
                || Files.exists(memoryCatalog.indexFile())) {
            memoryStore.ensureGitIgnore();
        }
        return memoryCatalog;
    }

    private static Map<String, String> modelIdentityMetadata(RuntimeSettings settings, boolean fixedModelClient) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        if (fixedModelClient) {
            metadata.put(PromptAssembler.PROVIDER_METADATA_KEY, "fixed-test-client");
            metadata.put(PromptAssembler.MODEL_METADATA_KEY, "fixed-test-client");
            return metadata;
        }

        metadata.put(PromptAssembler.PROVIDER_METADATA_KEY, settings.provider());
        if ("openai-compatible".equals(settings.provider())) {
            if (settings.model() != null) {
                metadata.put(PromptAssembler.MODEL_METADATA_KEY, settings.model());
            }
            metadata.put(PromptAssembler.BASE_URL_METADATA_KEY, settings.baseUrl());
        } else if ("echo".equals(settings.provider())) {
            metadata.put(PromptAssembler.MODEL_METADATA_KEY, "echo");
        }
        return Map.copyOf(metadata);
    }

    private static TraceSink stderrTraceSink(CommandSpec spec, TerminalStyle style) {
        return event -> {
            spec.commandLine().getErr().println(style.trace(event));
            spec.commandLine().getErr().flush();
        };
    }

    private static TraceSink stderrTraceSink(CommandSpec spec, ChatConsole chatConsole, TerminalStyle style) {
        return event -> {
            chatConsole.beforeAsyncOutput();
            try {
                spec.commandLine().getErr().println(style.trace(event));
                spec.commandLine().getErr().flush();
            } finally {
                chatConsole.afterAsyncOutput(false);
            }
        };
    }

    private static boolean isCronTriggered(PermissionRequest request) {
        return "cron".equals(request.metadata().get("agent.trigger"));
    }

    private static McpToolProvider createMcpToolProvider(RuntimeSettings settings) {
        if (!settings.mcpTools()) {
            return McpToolProvider.empty();
        }
        McpConfig config = settings.mcpConfigPath() == null
                ? McpConfig.loadDefault(WORKSPACE_ROOT)
                : McpConfig.load(WORKSPACE_ROOT, settings.mcpConfigPath());
        return McpToolProvider.start(config);
    }

    private static RuntimeProfileResolution resolveRuntimeProfile(
            String profile,
            RuntimeProfileOverrides overrides,
            Map<String, String> environment
    ) {
        return new RuntimeProfileResolver().resolve(WORKSPACE_ROOT, profile, overrides, environment);
    }

    private static ToolRegistry applyRuntimeToolFilter(ToolRegistry registry, RuntimeSettings settings) {
        return RuntimeToolFilter.filter(registry, settings);
    }

    private static Map<String, String> openAiEnvironment(RuntimeSettings settings, Map<String, String> environment) {
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>(environment);
        if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) {
            resolved.put("OPENAI_BASE_URL", settings.baseUrl());
        }
        return Map.copyOf(resolved);
    }

    private static ToolRegistry withMcpTools(ToolRegistry baseRegistry, McpToolProvider mcpToolProvider) {
        if (mcpToolProvider.isEmpty()) {
            return baseRegistry;
        }
        ArrayList<Tool> tools = new ArrayList<>(baseRegistry.tools());
        tools.addAll(mcpToolProvider.tools());
        return ToolRegistry.of(tools);
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

        @Option(names = "--profile", description = "Runtime profile: dev, readonly, or a project profile in .agent/profiles.")
        private String profile;

        @Option(names = "--provider", description = "Provider to use: echo, openai-compatible.")
        private String provider;

        @Option(names = "--model", description = "Model name for --provider openai-compatible. Defaults to OPENAI_MODEL.")
        private String model;

        @Option(names = "--system", description = "System prompt for the agent.")
        private String systemPrompt;

        @Option(names = "--agent-name", defaultValue = "main", description = "Runtime agent name used for task ownership.")
        private String agentName;

        @Option(names = "--no-context-compression", description = "Disable context compression for this run.")
        private boolean noContextCompression;

        @Option(names = "--context-window-tokens", description = "Estimated context window tokens.")
        private Integer contextWindowTokens;

        @Option(names = "--max-output-tokens", description = "Estimated output budget reserved for context compression.")
        private Integer maxOutputTokens;

        @Option(names = "--reserved-tokens", description = "Estimated reserved tokens for safety margin.")
        private Integer reservedTokens;

        @Option(names = "--no-error-recovery", description = "Disable model-call error recovery.")
        private boolean noErrorRecovery;

        @Option(names = "--model-retry-attempts", description = "Transient model error retry attempts.")
        private Integer modelRetryAttempts;

        @Option(names = "--generation-max-output-tokens", description = "Generation max output tokens sent to the provider.")
        private Integer generationMaxOutputTokens;

        @Option(names = "--recovery-max-output-tokens", description = "Max output tokens used for output-truncation recovery.")
        private Integer recoveryMaxOutputTokens;

        @Option(names = "--trace", description = "Print safe runtime trace events to stderr.")
        private boolean trace;

        @Option(names = "--color", defaultValue = "auto", description = "Color output: auto, always, never.")
        private String color;

        @Option(names = "--no-mcp", description = "Disable project MCP tools from .mcp.json.")
        private boolean noMcp;

        @Option(names = "--mcp-config", description = "MCP config path inside the workspace. Defaults to .mcp.json.")
        private Path mcpConfig;

        @Parameters(arity = "0..*", paramLabel = "TASK", description = "Task for the agent to run.")
        private List<String> taskParts;

        @Override
        public Integer call() throws IOException {
            TerminalStyle terminalStyle;
            try {
                terminalStyle = createTerminalStyle();
            } catch (IllegalArgumentException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.USAGE;
            }
            String task = resolveTask();
            if (task.isBlank()) {
                spec.commandLine().getErr().println(terminalStyle.error("Missing task. Pass TASK arguments, type one at the prompt, or pipe one through stdin."));
                return CommandLine.ExitCode.USAGE;
            }

            AgentResult result;
            try {
                RuntimeSettings settings = resolveRuntimeProfile(profile, runtimeOverrides(), environment).settings();
                try (McpToolProvider mcpToolProvider = createMcpToolProvider(settings)) {
                    DefaultAgent agent;
                    ModelOptions modelOptions;
                    modelOptions = createModelOptions(settings);
                    ModelClient modelClient = createModelClient(settings);
                    BackgroundTaskManager backgroundTaskManager = BackgroundTaskManager.disabled();
                    UserApprover userApprover = createUserApprover(terminalStyle);
                    SkillCatalog skillCatalog = SkillCatalog.scanDefault();
                    MemoryStore memoryStore = MemoryStore.defaultStore();
                    MemoryCatalog memoryCatalog = prepareMemoryCatalog(memoryStore);
                    printMemoryWarnings(memoryCatalog, terminalStyle);
                    ToolRegistry toolRegistry = applyRuntimeToolFilter(withMcpTools(
                            ToolRegistry.defaultToolsWithSubagent(modelClient, userApprover, skillCatalog, memoryCatalog),
                            mcpToolProvider
                    ), settings);
                    agent = new DefaultAgent(
                            modelClient,
                            toolRegistry,
                            createHookRegistry(toolRegistry, userApprover, modelClient, memoryStore, memoryCatalog, settings),
                            createContextManager(settings),
                            createErrorRecoveryConfig(settings),
                            backgroundTaskManager,
                            settings.traceEnabled() ? stderrTraceSink(spec, terminalStyle) : TraceSink.noop()
                    );
                    AgentRequest request = new AgentRequest(task, maxSteps, requestMetadata(settings), settings.system())
                            .withModelOptions(modelOptions);
                    result = agent.run(request);
                }
            } catch (IllegalArgumentException | RuntimeProfileException | OpenAiCompatibleException | McpException e) {
                spec.commandLine().getErr().println(terminalStyle.error(e.getMessage()));
                return CommandLine.ExitCode.USAGE;
            }

            spec.commandLine().getOut().println(result.output());
            return result.completed() ? 0 : 2;
        }

        private UserApprover createUserApprover(TerminalStyle terminalStyle) {
            return (request, decision) -> {
                if (isCronTriggered(request)) {
                    return false;
                }
                if (!interactiveApproval) {
                    return false;
                }
                spec.commandLine().getErr().println(terminalStyle.warning("Tool approval required:"));
                spec.commandLine().getErr().println("  tool: " + request.toolName());
                spec.commandLine().getErr().println("  reason: " + decision.reason());
                if (!decision.riskSummary().isBlank()) {
                    spec.commandLine().getErr().println("  risk: " + decision.riskSummary());
                }
                spec.commandLine().getErr().println("  arguments: " + summarizeArguments(request));
                spec.commandLine().getErr().print(terminalStyle.warning("Allow this tool call? [y/N] "));
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

        private RuntimeProfileOverrides runtimeOverrides() {
            return RuntimeProfileOverrides.builder()
                    .provider(provider)
                    .model(model)
                    .system(systemPrompt)
                    .mcpEnabled(noMcp ? false : null)
                    .mcpConfig(noMcp ? null : mcpConfig)
                    .contextCompressionEnabled(noContextCompression ? false : null)
                    .contextWindowTokens(contextWindowTokens)
                    .maxOutputTokens(maxOutputTokens)
                    .reservedTokens(reservedTokens)
                    .errorRecoveryEnabled(noErrorRecovery ? false : null)
                    .modelRetryAttempts(modelRetryAttempts)
                    .generationMaxOutputTokens(generationMaxOutputTokens)
                    .recoveryMaxOutputTokens(recoveryMaxOutputTokens)
                    .traceEnabled(trace ? true : null)
                    .build();
        }

        private ModelClient createModelClient(RuntimeSettings settings) {
            if (fixedModelClient != null) {
                return fixedModelClient;
            }
            return switch (settings.provider()) {
                case "echo" -> new EchoModelClient();
                case "openai-compatible" -> {
                    if (settings.model() == null || settings.model().isBlank()) {
                        throw new IllegalArgumentException("--model or OPENAI_MODEL is required when --provider openai-compatible.");
                    }
                    yield OpenAiCompatibleModelClient.fromEnvironment(settings.model(), openAiEnvironment(settings, environment));
                }
                default -> throw new IllegalArgumentException("Unknown provider: " + settings.provider());
            };
        }

        private ContextManager createContextManager(RuntimeSettings settings) {
            if (!settings.contextCompressionEnabled()) {
                return ContextManager.disabled();
            }
            return ContextManager.withConfig(new ContextCompressionConfig(
                    true,
                    settings.contextWindowTokens(),
                    settings.maxOutputTokens(),
                    settings.reservedTokens(),
                    ContextCompressionConfig.DEFAULT_TOOL_RESULT_BUDGET_CHARS,
                    ContextCompressionConfig.DEFAULT_MAX_MESSAGES,
                    ContextCompressionConfig.DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP,
                    ContextCompressionConfig.DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT,
                    ContextCompressionConfig.DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS,
                    ContextCompressionConfig.DEFAULT_MAX_AUTO_COMPACT_FAILURES
            ));
        }

        private ErrorRecoveryConfig createErrorRecoveryConfig(RuntimeSettings settings) {
            if (!settings.errorRecoveryEnabled()) {
                return ErrorRecoveryConfig.disabled();
            }
            return new ErrorRecoveryConfig(
                    true,
                    settings.modelRetryAttempts(),
                    settings.recoveryMaxOutputTokens(),
                    ErrorRecoveryConfig.DEFAULT_BASE_DELAY,
                    true
            );
        }

        private ModelOptions createModelOptions(RuntimeSettings settings) {
            return new ModelOptions(settings.generationMaxOutputTokens());
        }

        private Map<String, String> requestMetadata(RuntimeSettings settings) {
            LinkedHashMap<String, String> metadata = new LinkedHashMap<>(modelIdentityMetadata(settings, fixedModelClient != null));
            metadata.put(TaskManager.AGENT_NAME_METADATA_KEY, resolvedAgentName());
            return Map.copyOf(metadata);
        }

        private String resolvedAgentName() {
            return agentName == null || agentName.isBlank() ? "main" : agentName.strip();
        }

        private HookRegistry createHookRegistry(
                ToolRegistry toolRegistry,
                UserApprover userApprover,
                ModelClient modelClient,
                MemoryStore memoryStore,
                MemoryCatalog memoryCatalog,
                RuntimeSettings settings
        ) {
            PermissionChecker permissionChecker = new PolicyPermissionChecker(settings, WORKSPACE_ROOT);
            if (memoryWriteEnabled(settings)) {
                return HookRegistry.defaultsFor(
                        toolRegistry,
                        userApprover,
                        memoryCatalog,
                        new MemoryExtractor(modelClient),
                        memoryStore,
                        permissionChecker
                );
            }
            return HookRegistry.defaultsFor(toolRegistry, userApprover, memoryCatalog, permissionChecker);
        }

        private boolean memoryWriteEnabled(RuntimeSettings settings) {
            return fixedModelClient == null && settings.persistentMemory() && "openai-compatible".equals(settings.provider());
        }

        private void printMemoryWarnings(MemoryCatalog memoryCatalog, TerminalStyle terminalStyle) {
            for (String warning : memoryCatalog.warnings()) {
                spec.commandLine().getErr().println(terminalStyle.warning("Memory warning: " + warning));
            }
        }

        private TerminalStyle createTerminalStyle() {
            return TerminalStyle.of(color, interactiveApproval);
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

        @Option(names = "--profile", description = "Runtime profile: dev, readonly, or a project profile in .agent/profiles.")
        private String profile;

        @Option(names = "--provider", description = "Provider to use: echo, openai-compatible.")
        private String provider;

        @Option(names = "--model", description = "Model name for --provider openai-compatible. Defaults to OPENAI_MODEL.")
        private String model;

        @Option(names = "--system", description = "System prompt for the agent.")
        private String systemPrompt;

        @Option(names = "--agent-name", defaultValue = "main", description = "Runtime agent name used for task ownership.")
        private String agentName;

        @Option(names = "--no-context-compression", description = "Disable context compression for this chat session.")
        private boolean noContextCompression;

        @Option(names = "--context-window-tokens", description = "Estimated context window tokens.")
        private Integer contextWindowTokens;

        @Option(names = "--max-output-tokens", description = "Estimated output budget reserved for context compression.")
        private Integer maxOutputTokens;

        @Option(names = "--reserved-tokens", description = "Estimated reserved tokens for safety margin.")
        private Integer reservedTokens;

        @Option(names = "--no-error-recovery", description = "Disable model-call error recovery.")
        private boolean noErrorRecovery;

        @Option(names = "--model-retry-attempts", description = "Transient model error retry attempts.")
        private Integer modelRetryAttempts;

        @Option(names = "--generation-max-output-tokens", description = "Generation max output tokens sent to the provider.")
        private Integer generationMaxOutputTokens;

        @Option(names = "--recovery-max-output-tokens", description = "Max output tokens used for output-truncation recovery.")
        private Integer recoveryMaxOutputTokens;

        @Option(names = "--no-trace", description = "Disable safe runtime trace events in chat.")
        private boolean noTrace;

        @Option(names = "--color", defaultValue = "auto", description = "Color output: auto, always, never.")
        private String color;

        @Option(names = "--no-mcp", description = "Disable project MCP tools from .mcp.json.")
        private boolean noMcp;

        @Option(names = "--mcp-config", description = "MCP config path inside the workspace. Defaults to .mcp.json.")
        private Path mcpConfig;

        @Override
        public Integer call() throws IOException {
            TerminalStyle terminalStyle;
            try {
                terminalStyle = createTerminalStyle();
            } catch (IllegalArgumentException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.USAGE;
            }
            RuntimeSettings settings;
            try {
                settings = resolveRuntimeProfile(profile, runtimeOverrides(), environment).settings();
            } catch (RuntimeProfileException e) {
                spec.commandLine().getErr().println(terminalStyle.error(e.getMessage()));
                return CommandLine.ExitCode.USAGE;
            }
            try (
                    ChatConsole chatConsole = new ChatConsole(inputReader, spec.commandLine().getErr(), interactiveApproval, terminalStyle);
                    McpToolProvider mcpToolProvider = createMcpToolProvider(settings)
            ) {
                chatConsole.start();
                AgentSession session;
                ModelOptions modelOptions;
                CronScheduler cronScheduler;
                CronQueueProcessor cronQueueProcessor;
                TeamRuntime teamRuntime;
                TeamInboxPoller teamInboxPoller;
                modelOptions = createModelOptions(settings);
                ModelClient modelClient = createModelClient(settings);
                BackgroundTaskManager backgroundTaskManager = BackgroundTaskManager.defaults();
                CronQueue cronQueue = new CronQueue();
                ScheduledTaskManager scheduledTaskManager = new ScheduledTaskManager(ScheduledJobStore.defaultStore(), cronQueue);
                TaskManager taskManager = TaskManager.defaultManager();
                UserApprover userApprover = createUserApprover(chatConsole);
                SkillCatalog skillCatalog = SkillCatalog.scanDefault();
                MemoryStore memoryStore = MemoryStore.defaultStore();
                MemoryCatalog memoryCatalog = prepareMemoryCatalog(memoryStore);
                printMemoryWarnings(memoryCatalog, terminalStyle);
                printScheduledTaskWarnings(scheduledTaskManager, terminalStyle);
                teamRuntime = new TeamRuntime(
                        Path.of(""),
                        resolvedAgentName(),
                        modelClient,
                        userApprover,
                        skillCatalog,
                        memoryCatalog,
                        taskManager,
                        modelOptions,
                        settings.system(),
                        requestMetadata(settings),
                        new PolicyPermissionChecker(settings, WORKSPACE_ROOT)
                );
                ToolRegistry toolRegistry = applyRuntimeToolFilter(withMcpTools(
                        ToolRegistry.defaultToolsWithSubagent(
                                modelClient,
                                userApprover,
                                skillCatalog,
                                memoryCatalog,
                                scheduledTaskManager,
                                taskManager,
                                teamRuntime
                        ),
                        mcpToolProvider
                ), settings);
                session = new AgentSession(new DefaultAgent(
                        modelClient,
                        toolRegistry,
                        createHookRegistry(toolRegistry, userApprover, modelClient, memoryStore, memoryCatalog, settings),
                        createContextManager(settings),
                        createErrorRecoveryConfig(settings),
                        backgroundTaskManager,
                        settings.traceEnabled() ? stderrTraceSink(spec, chatConsole, terminalStyle) : TraceSink.noop()
                ), backgroundTaskManager);
                teamInboxPoller = new TeamInboxPoller(
                        teamRuntime,
                        session,
                        () -> requestMetadata(settings),
                        settings.system(),
                        maxSteps,
                        modelOptions,
                        execution -> {
                            chatConsole.beforeAsyncOutput();
                            try {
                                printTeamResult(execution, terminalStyle);
                            } finally {
                                chatConsole.afterAsyncOutput();
                            }
                        }
                );
                cronScheduler = new CronScheduler(scheduledTaskManager, cronQueue);
                cronQueueProcessor = new CronQueueProcessor(
                        cronQueue,
                        session,
                        task -> createCronRequest(task, modelOptions, settings),
                        execution -> {
                            chatConsole.beforeAsyncOutput();
                            try {
                                printCronResult(execution, terminalStyle);
                            } finally {
                                chatConsole.afterAsyncOutput();
                            }
                        }
                );

                cronScheduler.start();
                cronQueueProcessor.start();
                teamInboxPoller.start();
                chatConsole.markEventLoopThread();
                int lastExitCode = 0;
                try {
                    while (true) {
                        chatConsole.printChatPrompt();
                        ChatConsole.Event event = chatConsole.nextEvent();
                        if (event.type() == ChatConsole.EventType.EOF) {
                            return lastExitCode;
                        }
                        if (event.type() == ChatConsole.EventType.ERROR) {
                            spec.commandLine().getErr().println(terminalStyle.error("failed to read chat input: " + event.error().getMessage()));
                            return CommandLine.ExitCode.SOFTWARE;
                        }
                        String task = event.line().strip();
                        if (task.isBlank()) {
                            continue;
                        }
                        if ("/exit".equals(task) || "/quit".equals(task)) {
                            return 0;
                        }
                        if ("/clear".equals(task)) {
                            session.clear();
                            spec.commandLine().getOut().println(terminalStyle.warning("Session cleared."));
                            lastExitCode = 0;
                            continue;
                        }

                        AgentResult result;
                        try {
                            result = session.run(new AgentRequest(task, maxSteps, requestMetadata(settings), settings.system())
                                    .withModelOptions(modelOptions));
                        } catch (OpenAiCompatibleException e) {
                            spec.commandLine().getErr().println(terminalStyle.error(e.getMessage()));
                            lastExitCode = CommandLine.ExitCode.SOFTWARE;
                            continue;
                        }
                        chatConsole.waitForNoActiveApproval();
                        spec.commandLine().getOut().println(terminalStyle.assistantPrefix() + " " + result.output());
                        lastExitCode = result.completed() ? 0 : 2;
                    }
                } finally {
                    teamInboxPoller.close();
                    teamRuntime.close();
                    cronQueueProcessor.close();
                    cronScheduler.close();
                }
            } catch (IllegalArgumentException | RuntimeProfileException | OpenAiCompatibleException | McpException e) {
                spec.commandLine().getErr().println(terminalStyle.error(e.getMessage()));
                return CommandLine.ExitCode.USAGE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CommandLine.ExitCode.SOFTWARE;
            }
        }

        private AgentRequest createCronRequest(CronTriggeredTask task, ModelOptions modelOptions, RuntimeSettings settings) {
            LinkedHashMap<String, String> metadata = new LinkedHashMap<>(requestMetadata(settings));
            metadata.put("agent.trigger", "cron");
            metadata.put("agent.cron.jobId", task.job().jobId());
            metadata.put("agent.cron.name", task.job().name());
            metadata.put("agent.cron.type", task.job().type().value());
            return new AgentRequest(task.asAgentTask(), maxSteps, metadata, settings.system())
                    .withModelOptions(modelOptions);
        }

        private void printCronResult(CronExecutionResult execution, TerminalStyle terminalStyle) {
            String prefix = cronOutputPrefix(execution.task());
            String output;
            if (!execution.success()) {
                output = "failed: " + execution.error();
            } else if (execution.result() == null) {
                output = "failed: no result";
            } else {
                output = execution.result().output();
            }
            java.io.PrintWriter writer = spec.commandLine().getOut();
            synchronized (writer) {
                writer.println(terminalStyle.cronPrefix(prefix) + " " + output);
                writer.flush();
            }
        }

        private String cronOutputPrefix(CronTriggeredTask task) {
            String name = task.job().name();
            if (name == null || name.isBlank()) {
                return "[cron " + task.job().jobId() + "]";
            }
            return "[cron " + task.job().jobId() + " " + name + "]";
        }

        private void printTeamResult(TeamExecutionResult execution, TerminalStyle terminalStyle) {
            String prefix = teamOutputPrefix(execution.messages());
            String output;
            if (!execution.success()) {
                output = "failed: " + execution.error();
            } else if (execution.result() == null) {
                output = "failed: no result";
            } else {
                output = execution.result().output();
            }
            java.io.PrintWriter writer = spec.commandLine().getOut();
            synchronized (writer) {
                writer.println(terminalStyle.teamPrefix(prefix) + " " + output);
                writer.flush();
            }
        }

        private String teamOutputPrefix(List<TeamMessage> messages) {
            if (messages == null || messages.isEmpty()) {
                return "[team inbox]";
            }
            return "[team " + messages.get(0).from() + "]";
        }

        private void printScheduledTaskWarnings(ScheduledTaskManager scheduledTaskManager, TerminalStyle terminalStyle) {
            for (String warning : scheduledTaskManager.warnings()) {
                spec.commandLine().getErr().println(terminalStyle.warning("Scheduled task warning: " + warning));
            }
        }

        private UserApprover createUserApprover(ChatConsole chatConsole) {
            return (request, decision) -> {
                if (isCronTriggered(request)) {
                    return false;
                }
                return chatConsole.approve(request, decision);
            };
        }

        private static String summarizeArguments(PermissionRequest request) {
            String value = request.arguments().toString();
            if (value.length() <= 500) {
                return value;
            }
            return value.substring(0, 500) + "...[truncated]";
        }

        private RuntimeProfileOverrides runtimeOverrides() {
            return RuntimeProfileOverrides.builder()
                    .provider(provider)
                    .model(model)
                    .system(systemPrompt)
                    .mcpEnabled(noMcp ? false : null)
                    .mcpConfig(noMcp ? null : mcpConfig)
                    .contextCompressionEnabled(noContextCompression ? false : null)
                    .contextWindowTokens(contextWindowTokens)
                    .maxOutputTokens(maxOutputTokens)
                    .reservedTokens(reservedTokens)
                    .errorRecoveryEnabled(noErrorRecovery ? false : null)
                    .modelRetryAttempts(modelRetryAttempts)
                    .generationMaxOutputTokens(generationMaxOutputTokens)
                    .recoveryMaxOutputTokens(recoveryMaxOutputTokens)
                    .traceEnabled(noTrace ? false : (profile == null || profile.isBlank() ? true : null))
                    .build();
        }

        private ModelClient createModelClient(RuntimeSettings settings) {
            if (fixedModelClient != null) {
                return fixedModelClient;
            }
            return switch (settings.provider()) {
                case "echo" -> new EchoModelClient();
                case "openai-compatible" -> {
                    if (settings.model() == null || settings.model().isBlank()) {
                        throw new IllegalArgumentException("--model or OPENAI_MODEL is required when --provider openai-compatible.");
                    }
                    yield OpenAiCompatibleModelClient.fromEnvironment(settings.model(), openAiEnvironment(settings, environment));
                }
                default -> throw new IllegalArgumentException("Unknown provider: " + settings.provider());
            };
        }

        private ContextManager createContextManager(RuntimeSettings settings) {
            if (!settings.contextCompressionEnabled()) {
                return ContextManager.disabled();
            }
            return ContextManager.withConfig(new ContextCompressionConfig(
                    true,
                    settings.contextWindowTokens(),
                    settings.maxOutputTokens(),
                    settings.reservedTokens(),
                    ContextCompressionConfig.DEFAULT_TOOL_RESULT_BUDGET_CHARS,
                    ContextCompressionConfig.DEFAULT_MAX_MESSAGES,
                    ContextCompressionConfig.DEFAULT_RECENT_TOOL_RESULTS_TO_KEEP,
                    ContextCompressionConfig.DEFAULT_RECENT_LOGICAL_ITEMS_AFTER_AUTO_COMPACT,
                    ContextCompressionConfig.DEFAULT_REACTIVE_RECENT_LOGICAL_ITEMS,
                    ContextCompressionConfig.DEFAULT_MAX_AUTO_COMPACT_FAILURES
            ));
        }

        private ErrorRecoveryConfig createErrorRecoveryConfig(RuntimeSettings settings) {
            if (!settings.errorRecoveryEnabled()) {
                return ErrorRecoveryConfig.disabled();
            }
            return new ErrorRecoveryConfig(
                    true,
                    settings.modelRetryAttempts(),
                    settings.recoveryMaxOutputTokens(),
                    ErrorRecoveryConfig.DEFAULT_BASE_DELAY,
                    true
            );
        }

        private ModelOptions createModelOptions(RuntimeSettings settings) {
            return new ModelOptions(settings.generationMaxOutputTokens());
        }

        private Map<String, String> requestMetadata(RuntimeSettings settings) {
            LinkedHashMap<String, String> metadata = new LinkedHashMap<>(modelIdentityMetadata(settings, fixedModelClient != null));
            metadata.put(TaskManager.AGENT_NAME_METADATA_KEY, resolvedAgentName());
            return Map.copyOf(metadata);
        }

        private String resolvedAgentName() {
            return agentName == null || agentName.isBlank() ? "main" : agentName.strip();
        }

        private HookRegistry createHookRegistry(
                ToolRegistry toolRegistry,
                UserApprover userApprover,
                ModelClient modelClient,
                MemoryStore memoryStore,
                MemoryCatalog memoryCatalog,
                RuntimeSettings settings
        ) {
            PermissionChecker permissionChecker = new PolicyPermissionChecker(settings, WORKSPACE_ROOT);
            if (memoryWriteEnabled(settings)) {
                return HookRegistry.defaultsFor(
                        toolRegistry,
                        userApprover,
                        memoryCatalog,
                        new MemoryExtractor(modelClient),
                        memoryStore,
                        permissionChecker
                );
            }
            return HookRegistry.defaultsFor(toolRegistry, userApprover, memoryCatalog, permissionChecker);
        }

        private boolean memoryWriteEnabled(RuntimeSettings settings) {
            return fixedModelClient == null && settings.persistentMemory() && "openai-compatible".equals(settings.provider());
        }

        private void printMemoryWarnings(MemoryCatalog memoryCatalog, TerminalStyle terminalStyle) {
            for (String warning : memoryCatalog.warnings()) {
                spec.commandLine().getErr().println(terminalStyle.warning("Memory warning: " + warning));
            }
        }

        private TerminalStyle createTerminalStyle() {
            return TerminalStyle.of(color, interactiveApproval);
        }

    }

    static final class ChatConsole implements AutoCloseable {

        private final BufferedReader inputReader;
        private final java.io.PrintWriter err;
        private final boolean interactive;
        private final TerminalStyle terminalStyle;
        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final Deque<Event> deferred = new ArrayDeque<>();
        private final Object approvalLock = new Object();
        private volatile boolean closed;
        private volatile boolean waitingForInput;
        private volatile PendingApproval activeApproval;
        private boolean promptVisible;
        private Thread readerThread;
        private Thread eventLoopThread;

        ChatConsole(BufferedReader inputReader, java.io.PrintWriter err, boolean interactive) {
            this(inputReader, err, interactive, TerminalStyle.plain());
        }

        ChatConsole(BufferedReader inputReader, java.io.PrintWriter err, boolean interactive, TerminalStyle terminalStyle) {
            this.inputReader = Objects.requireNonNull(inputReader, "inputReader must not be null");
            this.err = Objects.requireNonNull(err, "err must not be null");
            this.interactive = interactive;
            this.terminalStyle = Objects.requireNonNull(terminalStyle, "terminalStyle must not be null");
        }

        void start() {
            readerThread = new Thread(this::readLoop, "agent-chat-input-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        void markEventLoopThread() {
            this.eventLoopThread = Thread.currentThread();
        }

        void printChatPrompt() {
            if (!interactive) {
                return;
            }
            synchronized (err) {
                if (promptVisible || activeApproval != null) {
                    return;
                }
                err.print(terminalStyle.prompt("chat> "));
                promptVisible = true;
                err.flush();
            }
        }

        Event nextEvent() throws InterruptedException {
            Event event = deferred.pollFirst();
            if (event != null) {
                return prepareEvent(event);
            }
            waitingForInput = true;
            try {
                return prepareEvent(events.take());
            } finally {
                waitingForInput = false;
            }
        }

        void beforeAsyncOutput() {
            if (!interactive) {
                return;
            }
            synchronized (err) {
                if (promptVisible) {
                    err.println();
                    err.flush();
                    promptVisible = false;
                }
            }
        }

        void afterAsyncOutput() {
            afterAsyncOutput(true);
        }

        void afterAsyncOutput(boolean repaintPrompt) {
            if (!interactive || !waitingForInput) {
                return;
            }
            synchronized (err) {
                if (repaintPrompt && !promptVisible && activeApproval == null) {
                    err.print(terminalStyle.prompt("chat> "));
                    err.flush();
                    promptVisible = true;
                }
            }
        }

        boolean isWaitingForInput() {
            return waitingForInput;
        }

        void waitForNoActiveApproval() throws InterruptedException {
            synchronized (approvalLock) {
                while (activeApproval != null && !closed) {
                    approvalLock.wait();
                }
            }
        }

        boolean approve(PermissionRequest request, io.github.mengru.agent.core.permission.PermissionDecision decision) {
            if (!interactive || closed) {
                return false;
            }
            PendingApproval approval = new PendingApproval(request, decision);
            try {
                synchronized (approvalLock) {
                    while (activeApproval != null && !closed) {
                        approvalLock.wait();
                    }
                    if (closed) {
                        return false;
                    }
                    activeApproval = approval;
                    beforeAsyncOutput();
                    printApprovalPrompt(approval);
                    completeFromQueuedInput(approval);
                }
                return approval.result().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                return false;
            } finally {
                synchronized (approvalLock) {
                    if (activeApproval == approval) {
                        activeApproval = null;
                    }
                    approvalLock.notifyAll();
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            synchronized (approvalLock) {
                if (activeApproval != null) {
                    activeApproval.complete(false);
                    activeApproval = null;
                }
                approvalLock.notifyAll();
            }
        }

        private Event markPromptConsumed(Event event) {
            synchronized (err) {
                promptVisible = false;
            }
            return event;
        }

        private Event prepareEvent(Event event) {
            return markPromptConsumed(event);
        }

        private void printApprovalPrompt(PendingApproval approval) {
            PermissionRequest request = approval.request();
            io.github.mengru.agent.core.permission.PermissionDecision decision = approval.decision();
            synchronized (err) {
                promptVisible = false;
                err.println(terminalStyle.warning("Tool approval required:"));
                if (!request.metadata().getOrDefault("agent.name", "").isBlank()) {
                    err.println("  requester: " + request.metadata().get("agent.name"));
                }
                if (!request.metadata().getOrDefault("agent.trigger", "").isBlank()) {
                    err.println("  trigger: " + request.metadata().get("agent.trigger"));
                }
                if (!request.metadata().getOrDefault("agent.team.role", "").isBlank()) {
                    err.println("  team role: " + request.metadata().get("agent.team.role"));
                }
                if (!request.metadata().getOrDefault("agent.permission.reviewer", "").isBlank()) {
                    err.println("  reviewed by: " + request.metadata().get("agent.permission.reviewer"));
                }
                if (!request.metadata().getOrDefault("agent.permission.review.reason", "").isBlank()) {
                    err.println("  review: " + request.metadata().get("agent.permission.review.reason"));
                }
                err.println("  tool: " + request.toolName());
                err.println("  reason: " + decision.reason());
                if (!decision.riskSummary().isBlank()) {
                    err.println("  risk: " + decision.riskSummary());
                }
                err.println("  arguments: " + summarizeArguments(request));
                err.print(terminalStyle.warning("Allow this tool call? [y/N] "));
                err.flush();
            }
        }

        private void completeFromQueuedInput(PendingApproval approval) {
            Event event = events.poll();
            if (event == null || approval.result().isDone()) {
                return;
            }
            if (event.type() == EventType.USER_LINE) {
                approval.complete(isApprovalYes(event.line()));
                return;
            }
            if (event.type() == EventType.EOF || event.type() == EventType.ERROR) {
                approval.complete(false);
            }
        }

        private static String summarizeArguments(PermissionRequest request) {
            String value = request.arguments().toString();
            if (value.length() <= 500) {
                return value;
            }
            return value.substring(0, 500) + "...[truncated]";
        }

        private void readLoop() {
            try {
                while (!closed) {
                    String line = inputReader.readLine();
                    if (line == null) {
                        PendingApproval approval = activeApproval;
                        if (approval != null && !approval.result().isDone()) {
                            approval.complete(false);
                        }
                        events.offer(Event.eof());
                        return;
                    }
                    PendingApproval approval = activeApproval;
                    if (approval != null && !approval.result().isDone()) {
                        approval.complete(isApprovalYes(line));
                        continue;
                    }
                    events.offer(Event.userLine(line));
                }
            } catch (IOException e) {
                events.offer(Event.error(e));
            }
        }

        private static boolean isApprovalYes(String line) {
            String answer = line == null ? "" : line.trim();
            return "y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer);
        }

        enum EventType {
            USER_LINE,
            EOF,
            ERROR
        }

        record Event(EventType type, String line, PendingApproval approval, IOException error) {

            static Event userLine(String line) {
                return new Event(EventType.USER_LINE, line == null ? "" : line, null, null);
            }

            static Event eof() {
                return new Event(EventType.EOF, "", null, null);
            }

            static Event error(IOException error) {
                return new Event(EventType.ERROR, "", null, Objects.requireNonNull(error, "error must not be null"));
            }
        }

        record PendingApproval(
                PermissionRequest request,
                io.github.mengru.agent.core.permission.PermissionDecision decision,
                CompletableFuture<Boolean> result
        ) {

            PendingApproval(PermissionRequest request, io.github.mengru.agent.core.permission.PermissionDecision decision) {
                this(
                        Objects.requireNonNull(request, "request must not be null"),
                        Objects.requireNonNull(decision, "decision must not be null"),
                        new CompletableFuture<>()
                );
            }

            void complete(boolean approved) {
                result.complete(approved);
            }
        }
    }

    @Command(
            name = "profile",
            description = "Inspect runtime profiles."
    )
    static final class ProfileCommand implements Callable<Integer> {

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(System.out);
            return 0;
        }
    }

    @Command(name = "show", description = "Show the sanitized effective runtime profile.")
    static final class ProfileShowCommand implements Callable<Integer> {

        private final Map<String, String> environment;

        ProfileShowCommand() {
            this(System.getenv());
        }

        ProfileShowCommand(Map<String, String> environment) {
            this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        }

        @Spec
        private CommandSpec spec;

        @Parameters(paramLabel = "NAME", description = "Profile name: dev, readonly, or a project profile.")
        private String name;

        @Override
        public Integer call() {
            try {
                RuntimeProfileResolution resolution = resolveRuntimeProfile(name, RuntimeProfileOverrides.none(), environment);
                spec.commandLine().getOut().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(resolution.settings().sanitizedMap()));
                return 0;
            } catch (RuntimeProfileException e) {
                spec.commandLine().getErr().println(e.getMessage());
                return CommandLine.ExitCode.USAGE;
            } catch (IOException e) {
                spec.commandLine().getErr().println("failed to render profile: " + e.getMessage());
                return CommandLine.ExitCode.SOFTWARE;
            }
        }
    }

    @Command(
            name = "memory",
            description = "Inspect local persistent agent memory.",
            subcommands = {MemoryListCommand.class, MemoryShowCommand.class}
    )
    static final class MemoryCommand implements Callable<Integer> {

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(System.out);
            return 0;
        }
    }

    @Command(name = "list", description = "List local persistent memories.")
    static final class MemoryListCommand implements Callable<Integer> {

        private final Path workspace;

        MemoryListCommand() {
            this(Path.of(""));
        }

        MemoryListCommand(Path workspace) {
            this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        }

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            MemoryCatalog catalog = MemoryCatalog.scan(workspace);
            for (String warning : catalog.warnings()) {
                spec.commandLine().getErr().println("Memory warning: " + warning);
            }
            if (catalog.isEmpty()) {
                spec.commandLine().getOut().println("No memories.");
                return 0;
            }
            spec.commandLine().getOut().println(catalog.renderIndex());
            return 0;
        }
    }

    @Command(name = "show", description = "Show one local persistent memory by name or file.")
    static final class MemoryShowCommand implements Callable<Integer> {

        private final Path workspace;

        MemoryShowCommand() {
            this(Path.of(""));
        }

        MemoryShowCommand(Path workspace) {
            this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
        }

        @Spec
        private CommandSpec spec;

        @Parameters(paramLabel = "NAME_OR_FILE", description = "Memory name or .md file name.")
        private String nameOrFile;

        @Override
        public Integer call() {
            MemoryCatalog catalog = MemoryCatalog.scan(workspace);
            for (String warning : catalog.warnings()) {
                spec.commandLine().getErr().println("Memory warning: " + warning);
            }
            MemoryDefinition memory = catalog.find(nameOrFile);
            if (memory == null) {
                spec.commandLine().getErr().println("Memory not found: " + nameOrFile);
                return CommandLine.ExitCode.USAGE;
            }
            spec.commandLine().getOut().println(memory.content());
            return 0;
        }
    }
}
