package io.github.mengru.agent.core.permission;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPermissionCheckerTest {

    @TempDir
    Path workspace;

    @Test
    void readFileAndGlobAreAllowedWithinWorkspace() {
        DefaultPermissionChecker checker = new DefaultPermissionChecker(workspace);
        ObjectNode todoArguments = args();
        todoArguments.putArray("todos");

        assertThat(checker.check(request("subagent", args().put("description", "inspect"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("todo_write", todoArguments)).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("load_skill", args().put("name", "java-agent"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("read_file", args().put("path", "README.md"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("glob", args().put("pattern", "**/*.java"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
    }

    @Test
    void mutationsAndBashRequireUserApprovalWithinWorkspace() {
        DefaultPermissionChecker checker = new DefaultPermissionChecker(workspace);

        assertThat(checker.check(request("write_file", args().put("path", "a.txt"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ASK_USER);
        assertThat(checker.check(request("edit_file", args().put("path", "a.txt"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ASK_USER);
        assertThat(checker.check(request("bash", args().put("command", "printf hello"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ASK_USER);
    }

    @Test
    void hardDeniesWorkspaceEscapeBeforeAsking() {
        DefaultPermissionChecker checker = new DefaultPermissionChecker(workspace);

        PermissionDecision decision = checker.check(request("write_file", args().put("path", "../outside.txt")));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("escapes workspace");
    }

    @Test
    void hardDeniesAlwaysBlockedBashCommands() {
        DefaultPermissionChecker checker = new DefaultPermissionChecker(workspace);

        assertThat(checker.check(request("bash", args().put("command", "sudo whoami"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(checker.check(request("bash", args().put("command", "rm -rf /"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
    }

    private static PermissionRequest request(String toolName, ObjectNode arguments) {
        return new PermissionRequest("call-1", toolName, arguments, Map.of());
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
