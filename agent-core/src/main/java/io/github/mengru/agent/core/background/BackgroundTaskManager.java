package io.github.mengru.agent.core.background;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mengru.agent.api.BackgroundTaskNotification;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.tool.local.BashTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class BackgroundTaskManager {

    private static final int DEFAULT_MAX_RUNNING_TASKS = 4;
    private static final List<Pattern> LONG_RUNNING_COMMAND_PATTERNS = List.of(
            Pattern.compile("(?is)(^|\\s)tail\\s+-f(\\s|$)"),
            Pattern.compile("(?is)(^|\\s)watch\\s+"),
            Pattern.compile("(?is)(^|\\s)sleep\\s+\\d+"),
            Pattern.compile("(?is)(^|\\s)(npm|pnpm|yarn)\\s+(run\\s+)?(dev|start)(\\s|$)"),
            Pattern.compile("(?is)(^|\\s)(vite|next\\s+dev|webpack\\s+--watch)(\\s|$)"),
            Pattern.compile("(?is)(^|\\s)mvn\\s+[^\\n;]*spring-boot:run(\\s|$)"),
            Pattern.compile("(?is)(^|\\s)(gradle|\\./gradlew)\\s+[^\\n;]*bootRun(\\s|$)"),
            Pattern.compile("(?is)(^|\\s)python\\s+-m\\s+http\\.server(\\s|$)")
    );

    private final boolean enabled;
    private final Semaphore runningSlots;
    private final int maxRunningTasks;
    private final AtomicLong nextId = new AtomicLong();
    private final ConcurrentLinkedQueue<BackgroundTaskNotification> completedNotifications = new ConcurrentLinkedQueue<>();

    private BackgroundTaskManager(boolean enabled, int maxRunningTasks) {
        if (maxRunningTasks < 1) {
            throw new IllegalArgumentException("maxRunningTasks must be greater than zero");
        }
        this.enabled = enabled;
        this.maxRunningTasks = maxRunningTasks;
        this.runningSlots = new Semaphore(maxRunningTasks);
    }

    public static BackgroundTaskManager defaults() {
        return new BackgroundTaskManager(true, DEFAULT_MAX_RUNNING_TASKS);
    }

    public static BackgroundTaskManager withMaxRunningTasks(int maxRunningTasks) {
        return new BackgroundTaskManager(true, maxRunningTasks);
    }

    public static BackgroundTaskManager disabled() {
        return new BackgroundTaskManager(false, DEFAULT_MAX_RUNNING_TASKS);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean shouldRunBashInBackground(ToolRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!enabled || !"bash".equals(request.toolName())) {
            return false;
        }
        JsonNode explicit = request.arguments().get("run_in_background");
        if (explicit != null && !explicit.isNull()) {
            return explicit.asBoolean(false);
        }
        String command = request.stringArgument("command").toLowerCase(Locale.ROOT).strip();
        return LONG_RUNNING_COMMAND_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(command).find());
    }

    public ToolResult startBash(BashTool bashTool, ToolRequest request) {
        Objects.requireNonNull(bashTool, "bashTool must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (!enabled) {
            return bashTool.execute(request);
        }
        if (!runningSlots.tryAcquire()) {
            return ToolResult.failure("background task limit reached: at most "
                    + maxRunningTasks + " tasks can run at the same time");
        }

        String bgId = "bg_" + nextId.incrementAndGet();
        String command = request.stringArgument("command");
        Thread worker = new Thread(
                () -> runBashTask(bgId, command, bashTool, request),
                "agent-background-" + bgId
        );
        worker.setDaemon(true);
        worker.start();

        return ToolResult.success("background task started"
                + "\nbg_id: " + bgId
                + "\ntool: bash"
                + "\nstatus: running"
                + "\ncommand:\n" + command);
    }

    public List<BackgroundTaskNotification> collectCompletedNotifications() {
        List<BackgroundTaskNotification> notifications = new ArrayList<>();
        BackgroundTaskNotification notification;
        while ((notification = completedNotifications.poll()) != null) {
            notifications.add(notification);
        }
        return notifications;
    }

    private void runBashTask(String bgId, String command, BashTool bashTool, ToolRequest request) {
        try {
            ToolResult result = bashTool.executeBackground(request);
            String status = result.success() ? "completed" : "failed";
            String content = "command:\n" + command + "\n\n"
                    + (result.success()
                    ? "result:\n" + result.output()
                    : "error:\n" + result.error());
            completedNotifications.add(new BackgroundTaskNotification(bgId, "bash", status, content));
        } catch (RuntimeException e) {
            completedNotifications.add(new BackgroundTaskNotification(
                    bgId,
                    "bash",
                    "failed",
                    "command:\n" + command + "\n\nerror:\nbackground task crashed: " + e.getMessage()
            ));
        } finally {
            runningSlots.release();
        }
    }
}
