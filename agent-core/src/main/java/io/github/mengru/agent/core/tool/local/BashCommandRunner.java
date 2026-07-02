package io.github.mengru.agent.core.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
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

final class BashCommandRunner {

    private static final List<Pattern> REJECTED_PATTERNS = List.of(
            Pattern.compile("(?is)(^|\\s)rm\\s+[^\\n;]*(?:-[^\\n;]*r|--recursive)"),
            Pattern.compile("(?is)(^|\\s)(sudo|su|shutdown|reboot|halt)($|\\s)"),
            Pattern.compile("(?is)(^|\\s)(chmod|chown)\\s+[^\\n;]*(?:/|\\.\\.)"),
            Pattern.compile("(?is)(^|\\s)(mkfs|dd)($|\\s)")
    );

    private final Path workspaceRoot;

    BashCommandRunner(Path workspaceRoot) {
        this.workspaceRoot = LocalToolSupport.normalizeWorkspace(workspaceRoot);
    }

    ToolResult run(JsonNode arguments, int defaultTimeoutSeconds, int maxTimeoutSeconds) {
        String command = arguments.path("command").asText("");
        if (command.isBlank()) {
            return ToolResult.failure("command must not be blank");
        }

        String rejection = rejectionReason(command);
        if (!rejection.isEmpty()) {
            return ToolResult.failure(rejection);
        }

        int timeoutSeconds = LocalToolSupport.intArgument(arguments, "timeoutSeconds", defaultTimeoutSeconds);
        if (timeoutSeconds < 1 || timeoutSeconds > maxTimeoutSeconds) {
            return ToolResult.failure("timeoutSeconds must be between 1 and " + maxTimeoutSeconds);
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
