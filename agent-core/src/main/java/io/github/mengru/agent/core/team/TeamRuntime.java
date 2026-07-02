package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.AgentResult;
import io.github.mengru.agent.api.ModelClient;
import io.github.mengru.agent.api.ModelOptions;
import io.github.mengru.agent.core.AgentSession;
import io.github.mengru.agent.core.DefaultAgent;
import io.github.mengru.agent.core.hook.HookRegistry;
import io.github.mengru.agent.core.memory.MemoryCatalog;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.permission.UserApprover;
import io.github.mengru.agent.core.prompt.PromptMode;
import io.github.mengru.agent.core.skill.SkillCatalog;
import io.github.mengru.agent.core.task.TaskManager;
import io.github.mengru.agent.core.tool.ToolRegistry;
import io.github.mengru.agent.core.trace.TraceSink;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TeamRuntime implements AutoCloseable {

    public static final int MAX_TEAMMATES = 4;
    public static final int TEAMMATE_MAX_STEPS = 10;
    public static final String TEAM_ID_METADATA_KEY = "agent.team.id";
    public static final String TEAM_ROLE_METADATA_KEY = "agent.team.role";
    public static final String TEAM_LEAD_METADATA_KEY = "agent.team.lead";

    private static final Duration PERMISSION_TIMEOUT = Duration.ofSeconds(120);

    private final String teamId;
    private final String leadName;
    private final MessageBus bus;
    private final ModelClient modelClient;
    private final UserApprover humanApprover;
    private final SkillCatalog skillCatalog;
    private final MemoryCatalog memoryCatalog;
    private final TaskManager taskManager;
    private final ModelOptions modelOptions;
    private final String userInstructions;
    private final Map<String, String> baseMetadata;
    private final Map<String, TeammateWorker> teammates = new ConcurrentHashMap<>();
    private final Map<String, PendingPermission> pendingPermissions = new ConcurrentHashMap<>();
    private final AtomicInteger nextTeammateId = new AtomicInteger();
    private volatile boolean closed;

    public TeamRuntime(
            Path workspace,
            String leadName,
            ModelClient modelClient,
            UserApprover humanApprover,
            SkillCatalog skillCatalog,
            MemoryCatalog memoryCatalog,
            TaskManager taskManager,
            ModelOptions modelOptions,
            String userInstructions,
            Map<String, String> baseMetadata
    ) {
        this.teamId = "team_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.leadName = MessageBus.validateAgentName(leadName == null || leadName.isBlank() ? "main" : leadName);
        this.bus = new MessageBus(workspace, teamId);
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.humanApprover = Objects.requireNonNull(humanApprover, "humanApprover must not be null");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog must not be null");
        this.memoryCatalog = Objects.requireNonNull(memoryCatalog, "memoryCatalog must not be null");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager must not be null");
        this.modelOptions = modelOptions == null ? ModelOptions.defaults() : modelOptions;
        this.userInstructions = userInstructions == null ? "" : userInstructions.strip();
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(baseMetadata == null ? Map.of() : baseMetadata);
        metadata.put(TaskManager.AGENT_NAME_METADATA_KEY, this.leadName);
        metadata.put(TEAM_ID_METADATA_KEY, teamId);
        metadata.put(TEAM_LEAD_METADATA_KEY, this.leadName);
        this.baseMetadata = Map.copyOf(metadata);
    }

    public String teamId() {
        return teamId;
    }

    public String leadName() {
        return leadName;
    }

    public MessageBus bus() {
        return bus;
    }

    public TaskManager taskManager() {
        return taskManager;
    }

    public synchronized TeammateView spawn(String requestedName, String role, String task, String instructions) {
        ensureOpen();
        String name = resolveTeammateName(requestedName);
        if (activeTeammateCount() >= MAX_TEAMMATES) {
            throw new IllegalStateException("background team limit reached: at most " + MAX_TEAMMATES + " teammates can be active");
        }
        String normalizedRole = requireNonBlank(role, "role");
        String normalizedTask = requireNonBlank(task, "task");
        TeammateWorker worker = new TeammateWorker(name, normalizedRole, normalizedTask, instructions == null ? "" : instructions.strip());
        teammates.put(name, worker);
        worker.start();
        sendInternal(TeamMessageType.TASK_ASSIGNMENT, leadName, name, normalizedTask, "", spawnPayload(normalizedRole, instructions));
        return worker.view();
    }

    public TeamMessage send(String from, String to, TeamMessageType type, String content, String correlationId, JsonNode payload) {
        ensureOpen();
        String sender = normalizeSender(from);
        String recipient = resolveRecipient(to);
        validateMessageDirection(sender, recipient, type);
        JsonNode outgoingPayload = payload == null ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
        String outgoingContent = content == null ? "" : content.strip();
        if (type == TeamMessageType.PERMISSION_RESPONSE) {
            PermissionResponse response = preparePermissionResponse(sender, correlationId, outgoingContent, outgoingPayload);
            outgoingPayload = response.payload();
            outgoingContent = response.content();
        }
        return sendInternal(type, sender, recipient, outgoingContent, correlationId, outgoingPayload);
    }

    public List<TeamMessage> consumeLeadMessages() {
        return bus.consume(leadName);
    }

    public List<TeammateView> listTeammates() {
        return teammates.values().stream()
                .map(TeammateWorker::view)
                .sorted(Comparator.comparing(TeammateView::name))
                .toList();
    }

    public UserApprover teammateApprover(String teammateName) {
        return (request, decision) -> requestPermission(teammateName, request, decision);
    }

    public Map<String, String> teammateMetadata(String teammateName, String role) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put(TaskManager.AGENT_NAME_METADATA_KEY, teammateName);
        metadata.put(TEAM_ROLE_METADATA_KEY, role);
        return Map.copyOf(metadata);
    }

    public String renderLeadInbox(List<TeamMessage> messages) {
        StringBuilder builder = new StringBuilder("<team_inbox team_id=\"")
                .append(teamId)
                .append("\">\n");
        for (TeamMessage message : messages) {
            builder.append("- type: ").append(message.type().value()).append('\n')
                    .append("  from: ").append(message.from()).append('\n')
                    .append("  to: ").append(message.to()).append('\n');
            if (!message.correlationId().isBlank()) {
                builder.append("  correlationId: ").append(message.correlationId()).append('\n');
            }
            builder.append("  content: ").append(message.content()).append('\n');
            if (message.payload().isObject() && message.payload().size() > 0) {
                builder.append("  payload: ").append(message.payload()).append('\n');
            }
        }
        builder.append("</team_inbox>");
        return builder.toString();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TeammateWorker teammate : teammates.values()) {
            teammate.requestShutdown();
        }
    }

    private boolean requestPermission(String teammateName, PermissionRequest request, PermissionDecision decision) {
        String correlationId = "perm_" + UUID.randomUUID();
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("toolCallId", request.toolCallId());
        payload.put("toolName", request.toolName());
        payload.set("arguments", request.arguments());
        payload.put("reason", decision.reason());
        payload.put("riskSummary", decision.riskSummary());
        PendingPermission pending = new PendingPermission(request, decision);
        pendingPermissions.put(correlationId, pending);
        TeammateWorker worker = teammates.get(teammateName);
        if (worker != null) {
            worker.setStatus(TeammateStatus.WAITING_PERMISSION, "waiting for permission " + correlationId);
        }
        sendInternal(
                TeamMessageType.PERMISSION_REQUEST,
                teammateName,
                leadName,
                "Permission requested for tool " + request.toolName() + ": " + decision.reason(),
                correlationId,
                payload
        );
        TeamMessage response = bus.awaitPermissionResponse(teammateName, correlationId, PERMISSION_TIMEOUT);
        pendingPermissions.remove(correlationId);
        if (worker != null) {
            worker.setStatus(TeammateStatus.ACTIVE, "permission response received");
        }
        return response != null
                && response.payload().path("approved").asBoolean(false);
    }

    private PermissionResponse preparePermissionResponse(String sender, String correlationId, String content, JsonNode payload) {
        if (!leadName.equals(sender)) {
            throw new IllegalArgumentException("only Lead can send permission_response");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("permission_response requires correlationId");
        }
        PendingPermission pending = pendingPermissions.get(correlationId);
        if (pending == null) {
            throw new IllegalArgumentException("unknown permission request: " + correlationId);
        }
        ObjectNode normalized = payload != null && payload.isObject()
                ? (ObjectNode) payload.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        boolean approved = normalized.path("approved").asBoolean(false);
        String normalizedContent = content;
        if (approved) {
            boolean humanApproved = humanApprover.approve(pending.request(), pending.decision());
            if (!humanApproved) {
                approved = false;
                normalizedContent = "human rejected teammate permission request";
            }
        }
        normalized.put("approved", approved);
        return new PermissionResponse(normalizedContent, normalized);
    }

    private TeamMessage sendInternal(TeamMessageType type, String from, String to, String content, String correlationId, JsonNode payload) {
        TeamMessage message = TeamMessage.create(teamId, type, from, to, content, correlationId, payload);
        bus.send(message);
        return message;
    }

    private void validateMessageDirection(String from, String to, TeamMessageType type) {
        if (type == TeamMessageType.PERMISSION_RESPONSE && !leadName.equals(from)) {
            throw new IllegalArgumentException("permission_response must be sent by Lead");
        }
        if (type == TeamMessageType.SHUTDOWN_REQUEST && !leadName.equals(from)) {
            throw new IllegalArgumentException("shutdown_request must be sent by Lead");
        }
        if (!leadName.equals(to) && !teammates.containsKey(to)) {
            throw new IllegalArgumentException("unknown teammate recipient: " + to);
        }
    }

    private String normalizeSender(String from) {
        String sender = from == null || from.isBlank() ? leadName : from.strip();
        if ("lead".equals(sender)) {
            return leadName;
        }
        return MessageBus.validateAgentName(sender);
    }

    private String resolveRecipient(String to) {
        String recipient = requireNonBlank(to, "to");
        if ("lead".equals(recipient)) {
            return leadName;
        }
        return MessageBus.validateAgentName(recipient);
    }

    private String resolveTeammateName(String requestedName) {
        String name;
        if (requestedName == null || requestedName.isBlank()) {
            do {
                name = "teammate_" + nextTeammateId.incrementAndGet();
            } while (teammates.containsKey(name));
        } else {
            name = MessageBus.validateAgentName(requestedName);
        }
        if ("lead".equals(name) || leadName.equals(name)) {
            throw new IllegalArgumentException("teammate name cannot be lead or the Lead agent name");
        }
        if (teammates.containsKey(name)) {
            throw new IllegalArgumentException("teammate already exists: " + name);
        }
        return name;
    }

    private JsonNode spawnPayload(String role, String instructions) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("role", role);
        if (instructions != null && !instructions.isBlank()) {
            payload.put("instructions", instructions.strip());
        }
        return payload;
    }

    private long activeTeammateCount() {
        return teammates.values().stream()
                .filter(teammate -> teammate.status != TeammateStatus.TERMINATED)
                .count();
    }

    private String teammateInstructions(String role, String instructions) {
        String extra = instructions == null || instructions.isBlank() ? "" : "\n\nAdditional teammate instructions:\n" + instructions.strip();
        return "You are teammate " + role + " in team " + teamId
                + ". Communicate with Lead through send_message. Do not spawn teammates."
                + extra;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("team runtime is closed");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private record PendingPermission(PermissionRequest request, PermissionDecision decision) {
    }

    private record PermissionResponse(String content, JsonNode payload) {
    }

    private final class TeammateWorker implements Runnable {
        private final String name;
        private final String role;
        private final String task;
        private final String instructions;
        private final Instant startedAt = Instant.now();
        private final AgentSession session;
        private volatile TeammateStatus status = TeammateStatus.STARTING;
        private volatile String lastMessage = "";
        private volatile boolean running = true;
        private Thread thread;

        private TeammateWorker(String name, String role, String task, String instructions) {
            this.name = name;
            this.role = role;
            this.task = task;
            this.instructions = instructions;
            ToolRegistry tools = ToolRegistry.teammateTools(TeamRuntime.this, taskManager);
            DefaultAgent agent = new DefaultAgent(
                    modelClient,
                    tools,
                    HookRegistry.defaultsFor(tools, teammateApprover(name), memoryCatalog, PromptMode.TEAMMATE)
            );
            this.session = new AgentSession(agent);
        }

        private void start() {
            thread = new Thread(this, "agent-teammate-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            try {
                setStatus(TeammateStatus.IDLE, "started");
                while (running) {
                    List<TeamMessage> messages = bus.consume(name);
                    if (messages.isEmpty()) {
                        sleepQuietly(1000);
                        continue;
                    }
                    for (TeamMessage message : messages) {
                        if (!running) {
                            break;
                        }
                        if (message.type() == TeamMessageType.SHUTDOWN_REQUEST) {
                            sendInternal(TeamMessageType.SHUTDOWN_ACK, name, leadName, "shutdown acknowledged by " + name, message.correlationId(), JsonNodeFactory.instance.objectNode());
                            running = false;
                            break;
                        }
                        if (message.type() == TeamMessageType.PERMISSION_RESPONSE) {
                            continue;
                        }
                        handleMessage(message);
                    }
                }
            } finally {
                setStatus(TeammateStatus.TERMINATED, "terminated");
                if (!closed) {
                    sendInternal(TeamMessageType.TEAMMATE_TERMINATED, name, leadName, name + " terminated", "", JsonNodeFactory.instance.objectNode());
                }
            }
        }

        private void handleMessage(TeamMessage message) {
            setStatus(TeammateStatus.ACTIVE, "processing " + message.type().value());
            AgentResult result = session.run(new AgentRequest(
                    renderTeammateTask(message),
                    TEAMMATE_MAX_STEPS,
                    teammateMetadata(name, role),
                    teammateInstructions(role, instructions)
            ).withModelOptions(modelOptions));
            String content = result.completed()
                    ? result.output()
                    : "teammate turn did not complete: " + result.output();
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("completed", result.completed());
            payload.put("sourceMessageId", message.messageId());
            sendInternal(TeamMessageType.STATUS_UPDATE, name, leadName, content, "", payload);
            setStatus(TeammateStatus.IDLE, "idle");
        }

        private String renderTeammateTask(TeamMessage message) {
            return """
                    <team_message team_id="%s" type="%s" from="%s">
                    %s
                    </team_message>
                    """.formatted(teamId, message.type().value(), message.from(), message.content()).strip();
        }

        private void requestShutdown() {
            sendInternal(TeamMessageType.SHUTDOWN_REQUEST, leadName, name, "chat session is closing", "", JsonNodeFactory.instance.objectNode());
        }

        private void setStatus(TeammateStatus status, String message) {
            this.status = status;
            this.lastMessage = message == null ? "" : message;
        }

        private TeammateView view() {
            return new TeammateView(name, role, status, startedAt, lastMessage);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
