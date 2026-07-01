package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.Tool;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;

    private static final List<Pattern> REJECTED_PATTERNS = List.of(
            Pattern.compile("(?is)(^|\\s)rm\\s+[^\\n;]*(?:-[^\\n;]*r|--recursive)"),
            Pattern.compile("(?is)(^|\\s)(sudo|su|shutdown|reboot|halt)($|\\s)"),
            Pattern.compile("(?is)(^|\\s)(chmod|chown)\\s+[^\\n;]*(?:/|\\.\\.)"),
            Pattern.compile("(?is)(^|\\s)(mkfs|dd)($|\\s)")
    );

    private final Path workspaceRoot;

    public BashTool() {
        this(LocalToolSupport.defaultWorkspaceRoot());
    }

    public BashTool(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a non-interactive shell command from the workspace with timeout and guardrails.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = LocalToolSupport.objectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("command", LocalToolSupport.stringProperty("Shell command to run non-interactively."));
        properties.set("timeoutSeconds", LocalToolSupport.integerProperty("Timeout in seconds. Defaults to 30, maximum 120.", 1, MAX_TIMEOUT_SECONDS));
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String command = request.stringArgument("command");
        if (command.isBlank()) {
            return ToolResult.failure("command must not be blank");
        }

        String rejection = rejectionReason(command);
        if (!rejection.isEmpty()) {
            return ToolResult.failure(rejection);
        }

        int timeoutSeconds = LocalToolSupport.intArgument(request.arguments(), "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return ToolResult.failure("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }

        ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
        builder.directory(workspaceRoot.toFile());
        try {
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure("command timed out after " + timeoutSeconds + " seconds");
            }
            int exitCode = process.exitValue();
            String output = "exitCode: " + exitCode
                    + "\nstdout:\n" + stdout.get()
                    + "\nstderr:\n" + stderr.get();
            return exitCode == 0
                    ? ToolResult.success(LocalToolSupport.truncate(output))
                    : ToolResult.failure(LocalToolSupport.truncate(output));
        } catch (IOException e) {
            return ToolResult.failure("failed to start command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("command interrupted");
        } catch (ExecutionException e) {
            return ToolResult.failure("failed to read command output: " + e.getMessage());
        }
    }

    private String rejectionReason(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("read ") || lower.startsWith("read") || lower.contains(" -it ") || lower.contains("--interactive")) {
            return "interactive commands are not allowed";
        }
        if (command.contains("..")) {
            return "command must not reference parent paths";
        }
        for (Pattern pattern : REJECTED_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return "command rejected by local safety guard";
            }
        }

        String root = workspaceRoot.toString();
        for (String token : command.split("\\s+")) {
            String cleaned = cleanToken(token);
            if (cleaned.startsWith("/") && !cleaned.equals(root) && !cleaned.startsWith(root + "/")) {
                return "absolute paths outside the workspace are not allowed: " + cleaned;
            }
        }
        return "";
    }

    private static String cleanToken(String token) {
        String cleaned = token;
        while (!cleaned.isEmpty() && "'\"([{".indexOf(cleaned.charAt(0)) >= 0) {
            cleaned = cleaned.substring(1);
        }
        while (!cleaned.isEmpty() && "'\".,;:)]}".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String read(java.io.InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[failed to read stream: " + e.getMessage() + "]";
        }
    }
}
