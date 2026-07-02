package io.github.mengru.agent.core.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultPermissionChecker implements PermissionChecker {

    private static final Pattern RM_RF_ROOT = Pattern.compile("(?is)(^|\\s)rm\\s+[^\\n;]*(?:-[^\\n;]*r|--recursive)[^\\n;]*(?:\\s|^)/(?:\\s|$)");
    private static final Pattern BLOCKED_COMMANDS = Pattern.compile("(?is)(^|\\s)(sudo|su|shutdown|reboot|halt|mkfs|dd)($|\\s)");

    private final Path workspaceRoot;

    public DefaultPermissionChecker() {
        this(Path.of(""));
    }

    public DefaultPermissionChecker(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public PermissionDecision check(PermissionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return switch (request.toolName()) {
            case "subagent" -> PermissionDecision.allow();
            case "todo_write" -> PermissionDecision.allow();
            case "load_skill" -> PermissionDecision.allow();
            case "schedule_task", "list_scheduled_tasks", "cancel_scheduled_task" -> PermissionDecision.allow();
            case "create_task", "list_tasks", "get_task", "can_start", "claim_task", "complete_task" -> PermissionDecision.allow();
            case "spawn_teammate", "send_message", "list_teammates" -> PermissionDecision.allow();
            case "read_file" -> checkPathTool(request, false);
            case "write_file", "edit_file" -> checkPathTool(request, true);
            case "glob" -> checkGlob(request);
            case "bash" -> checkBash(request);
            default -> PermissionDecision.allow();
        };
    }

    private PermissionDecision checkPathTool(PermissionRequest request, boolean askAfterHardChecks) {
        String rawPath = request.stringArgument("path");
        String pathError = validateWorkspacePath(rawPath);
        if (!pathError.isEmpty()) {
            return PermissionDecision.deny(pathError);
        }
        if (askAfterHardChecks) {
            return PermissionDecision.askUser(
                    request.toolName() + " modifies a workspace file",
                    "tool=" + request.toolName() + ", path=" + rawPath
            );
        }
        return PermissionDecision.allow();
    }

    private PermissionDecision checkGlob(PermissionRequest request) {
        String pattern = request.stringArgument("pattern");
        if (pattern.isBlank()) {
            return PermissionDecision.deny("glob pattern must not be blank");
        }
        if (Path.of(pattern).isAbsolute() || pattern.contains("..")) {
            return PermissionDecision.deny("glob pattern must stay within the workspace");
        }
        return PermissionDecision.allow();
    }

    private PermissionDecision checkBash(PermissionRequest request) {
        String command = request.stringArgument("command");
        if (command.isBlank()) {
            return PermissionDecision.deny("bash command must not be blank");
        }
        String lower = command.toLowerCase(Locale.ROOT);
        if (RM_RF_ROOT.matcher(command).find()) {
            return PermissionDecision.deny("rm -rf / is never allowed");
        }
        if (BLOCKED_COMMANDS.matcher(command).find()) {
            return PermissionDecision.deny("command contains an always-blocked executable");
        }
        if (lower.contains("read ") || lower.startsWith("read") || lower.contains(" -it ") || lower.contains("--interactive")) {
            return PermissionDecision.deny("interactive commands are not allowed");
        }
        if (command.contains("..")) {
            return PermissionDecision.deny("bash command must not reference parent paths");
        }
        String outsidePath = firstOutsideAbsolutePath(command);
        if (!outsidePath.isEmpty()) {
            return PermissionDecision.deny("absolute paths outside the workspace are not allowed: " + outsidePath);
        }
        return PermissionDecision.askUser("bash executes a workspace command", "command=" + command);
    }

    private String validateWorkspacePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "path must not be blank";
        }
        Path candidate = Path.of(rawPath);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : workspaceRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            return "path escapes workspace: " + rawPath;
        }
        return validateNoSymlinkEscape(resolved, rawPath);
    }

    private String validateNoSymlinkEscape(Path resolved, String rawPath) {
        try {
            Path rootReal = workspaceRoot.toRealPath();
            Path nearestExisting = resolved;
            while (nearestExisting != null && !Files.exists(nearestExisting, LinkOption.NOFOLLOW_LINKS)) {
                nearestExisting = nearestExisting.getParent();
            }
            if (nearestExisting == null) {
                return "path has no existing parent: " + rawPath;
            }
            Path real = nearestExisting.toRealPath();
            if (!real.startsWith(rootReal)) {
                return "path escapes workspace through a symbolic link: " + rawPath;
            }
            return "";
        } catch (IOException e) {
            return "failed to validate workspace path: " + rawPath + ": " + e.getMessage();
        }
    }

    private String firstOutsideAbsolutePath(String command) {
        String root = workspaceRoot.toString();
        for (String token : command.split("\\s+")) {
            String cleaned = cleanToken(token);
            if (cleaned.startsWith("/") && !cleaned.equals(root) && !cleaned.startsWith(root + "/")) {
                return cleaned;
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
}
