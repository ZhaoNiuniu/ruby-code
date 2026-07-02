package io.github.mengru.agent.core.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageBus {

    public static final String TEAMS_DIR_NAME = ".teams";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path workspace;
    private final String teamId;
    private final Path teamDir;
    private final Path inboxDir;

    public MessageBus(Path workspace, String teamId) {
        this.workspace = Objects.requireNonNull(workspace, "workspace must not be null").toAbsolutePath().normalize();
        this.teamId = Objects.requireNonNull(teamId, "teamId must not be null");
        this.teamDir = this.workspace.resolve(TEAMS_DIR_NAME).resolve(teamId);
        this.inboxDir = teamDir.resolve("inboxes");
        ensureGitIgnore();
    }

    public String teamId() {
        return teamId;
    }

    public Path teamDir() {
        return teamDir;
    }

    public void send(TeamMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (!teamId.equals(message.teamId())) {
            throw new IllegalArgumentException("message teamId does not match bus teamId");
        }
        withInboxLock(message.to(), () -> {
            Files.createDirectories(inboxDir);
            Files.writeString(
                    inboxFile(message.to()),
                    serialize(message) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
            return null;
        });
    }

    public List<TeamMessage> consume(String recipient) {
        String normalized = validateAgentName(recipient);
        return withInboxLock(normalized, () -> {
            Path inbox = inboxFile(normalized);
            if (!Files.exists(inbox)) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(inbox, StandardCharsets.UTF_8);
            Files.writeString(inbox, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            List<TeamMessage> messages = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    messages.add(parse(line));
                } catch (RuntimeException ignored) {
                    // Bad inbox lines are dropped; mailboxes are runtime state, not authoritative storage.
                }
            }
            return List.copyOf(messages);
        });
    }

    public TeamMessage awaitPermissionResponse(String recipient, String correlationId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<TeamMessage> consumed = consume(recipient);
            List<TeamMessage> unmatched = new ArrayList<>();
            TeamMessage match = null;
            for (TeamMessage message : consumed) {
                if (message.type() == TeamMessageType.PERMISSION_RESPONSE
                        && correlationId.equals(message.correlationId())) {
                    match = message;
                } else {
                    unmatched.add(message);
                }
            }
            for (TeamMessage message : unmatched) {
                send(message);
            }
            if (match != null) {
                return match;
            }
            sleepQuietly(250);
        }
        return null;
    }

    public TeamMessage parse(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            return new TeamMessage(
                    text(node, "messageId"),
                    text(node, "teamId"),
                    TeamMessageType.from(text(node, "type")),
                    text(node, "from"),
                    text(node, "to"),
                    java.time.Instant.parse(text(node, "createdAt")),
                    optionalText(node, "content"),
                    optionalText(node, "correlationId"),
                    node.get("payload")
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String serialize(TeamMessage message) {
        try {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("messageId", message.messageId());
            node.put("teamId", message.teamId());
            node.put("type", message.type().value());
            node.put("from", message.from());
            node.put("to", message.to());
            node.put("createdAt", message.createdAt().toString());
            node.put("content", message.content());
            node.put("correlationId", message.correlationId());
            node.set("payload", message.payload());
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String validateAgentName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("agent name must not be blank");
        }
        String stripped = value.strip();
        if (!stripped.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("agent name must contain only letters, digits, dot, underscore, or hyphen: " + stripped);
        }
        return stripped;
    }

    private Path inboxFile(String agentName) {
        return inboxDir.resolve(validateAgentName(agentName) + ".jsonl");
    }

    private Path lockFile(String agentName) {
        return inboxDir.resolve(validateAgentName(agentName) + ".lock");
    }

    private <T> T withInboxLock(String agentName, CheckedSupplier<T> action) {
        Path lockPath = lockFile(agentName).toAbsolutePath().normalize();
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockPath.toString(), ignored -> new Object());
        synchronized (jvmLock) {
            return withFileLock(lockPath, action);
        }
    }

    private <T> T withFileLock(Path lockPath, CheckedSupplier<T> action) {
        try {
            Files.createDirectories(inboxDir);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to access team inbox: " + e.getMessage(), e);
        }
    }

    public void ensureGitIgnore() {
        Path gitIgnore = gitIgnorePath();
        try {
            if (!Files.exists(gitIgnore)) {
                Files.writeString(gitIgnore, TEAMS_DIR_NAME + "/\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return;
            }
            String current = Files.readString(gitIgnore, StandardCharsets.UTF_8);
            if (!hasLine(current, TEAMS_DIR_NAME + "/")) {
                String updated = (current.endsWith("\n") || current.isBlank() ? current : current + "\n")
                        + TEAMS_DIR_NAME + "/\n";
                Files.writeString(gitIgnore, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to update .gitignore: " + e.getMessage(), e);
        }
    }

    private Path gitIgnorePath() {
        Path current = workspace;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current.resolve(".gitignore");
            }
            current = current.getParent();
        }
        return workspace.resolve(".gitignore");
    }

    private static boolean hasLine(String content, String expected) {
        for (String line : content.split("\n")) {
            if (line.strip().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws IOException;
    }
}
