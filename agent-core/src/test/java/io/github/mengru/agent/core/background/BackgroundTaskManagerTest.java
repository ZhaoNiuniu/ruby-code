package io.github.mengru.agent.core.background;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.api.BackgroundTaskNotification;
import io.github.mengru.agent.api.ToolRequest;
import io.github.mengru.agent.api.ToolResult;
import io.github.mengru.agent.core.tool.local.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTaskManagerTest {

    @TempDir
    Path workspace;

    @Test
    void explicitRunInBackgroundStartsTaskAndCollectsNotification() throws Exception {
        BackgroundTaskManager manager = BackgroundTaskManager.defaults();
        BashTool bash = new BashTool(workspace);

        ToolResult placeholder = manager.startBash(bash, request(args()
                .put("command", "printf done")
                .put("run_in_background", true)));

        assertThat(placeholder.success()).isTrue();
        assertThat(placeholder.output()).contains("bg_id: bg_1");
        assertThat(placeholder.output()).contains("status: running");

        List<BackgroundTaskNotification> notifications = waitForNotifications(manager);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).bgId()).isEqualTo("bg_1");
        assertThat(notifications.get(0).status()).isEqualTo("completed");
        assertThat(notifications.get(0).asMessage()).contains("<task_notification");
        assertThat(notifications.get(0).asMessage()).contains("done");
    }

    @Test
    void explicitFalseOverridesHeuristic() {
        BackgroundTaskManager manager = BackgroundTaskManager.defaults();

        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "sleep 30")
                .put("run_in_background", false)))).isFalse();
    }

    @Test
    void heuristicIsConservative() {
        BackgroundTaskManager manager = BackgroundTaskManager.defaults();

        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "sleep 30")))).isTrue();
        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "tail -f app.log")))).isTrue();
        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "mvn test")))).isFalse();
        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "mvn package")))).isFalse();
    }

    @Test
    void rejectsWhenRunningTaskLimitIsReached() {
        BackgroundTaskManager manager = BackgroundTaskManager.withMaxRunningTasks(1);
        BashTool bash = new BashTool(workspace);

        ToolResult first = manager.startBash(bash, request(args().put("command", "sleep 1")));
        ToolResult second = manager.startBash(bash, request(args().put("command", "sleep 1")));

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isFalse();
        assertThat(second.error()).contains("background task limit reached");
    }

    @Test
    void disabledManagerDoesNotBackgroundBash() {
        BackgroundTaskManager manager = BackgroundTaskManager.disabled();

        assertThat(manager.shouldRunBashInBackground(request(args()
                .put("command", "sleep 30")
                .put("run_in_background", true)))).isFalse();
    }

    private static List<BackgroundTaskNotification> waitForNotifications(BackgroundTaskManager manager) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            List<BackgroundTaskNotification> notifications = manager.collectCompletedNotifications();
            if (!notifications.isEmpty()) {
                return notifications;
            }
            Thread.sleep(25);
        }
        return List.of();
    }

    private static ToolRequest request(ObjectNode arguments) {
        return new ToolRequest("bash", arguments, Map.of());
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
