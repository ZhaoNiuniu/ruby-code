package io.github.mengru.agent.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mengru.agent.core.permission.DefaultPermissionChecker;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyPermissionCheckerTest {

    @TempDir
    Path workspace;

    @Test
    void readonlyPolicyDeniesWriteCommandTaskMutationAndMcpTools() {
        PolicyPermissionChecker checker = checker("readonly", BuiltInPolicies.policy("readonly"));

        assertThat(checker.check(request("write_file", args().put("path", "a.txt"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(checker.check(request("bash", args().put("command", "printf hi"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(checker.check(request("claim_task", args().put("taskId", "task_000001"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(checker.check(request("mcp__demo__echo", args().put("text", "hi"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.DENY);
    }

    @Test
    void readonlyPolicyAllowsReadOnlyTools() {
        PolicyPermissionChecker checker = checker("readonly", BuiltInPolicies.policy("readonly"));

        assertThat(checker.check(request("read_file", args().put("path", "README.md"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("glob", args().put("pattern", "**/*.java"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
        assertThat(checker.check(request("get_task", args().put("taskId", "task_000001"))).outcome())
                .isEqualTo(PermissionDecision.Outcome.ALLOW);
    }

    @Test
    void hardDenyStillWinsBeforePolicyAllow() {
        PolicyConfig policy = new PolicyConfig("ask", Map.of("write_file", "allow"), Map.of());
        PolicyPermissionChecker checker = checker("dev", policy);

        PermissionDecision decision = checker.check(request("write_file", args().put("path", "../outside.txt")));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.DENY);
        assertThat(decision.reason()).contains("escapes workspace");
    }

    @Test
    void devPolicyAsksForSoftRiskTools() {
        PolicyPermissionChecker checker = checker("dev", BuiltInPolicies.policy("dev"));

        PermissionDecision decision = checker.check(request("write_file", args().put("path", "a.txt")));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.ASK_USER);
        assertThat(decision.reason()).contains("modifies a workspace file");
    }

    @Test
    void policyCanAllowSoftRiskToolsAfterHardChecks() {
        PolicyConfig policy = new PolicyConfig("ask", Map.of("bash", "allow"), Map.of());
        PolicyPermissionChecker checker = checker("dev", policy);

        PermissionDecision decision = checker.check(request("bash", args().put("command", "printf hi")));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.ALLOW);
    }

    @Test
    void cronContextUsesStrictestPolicyAction() {
        PolicyPermissionChecker checker = checker("dev", BuiltInPolicies.policy("dev"));

        PermissionDecision decision = checker.check(request(
                "bash",
                args().put("command", "printf hi"),
                Map.of("agent.trigger", "cron")
        ));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.DENY);
    }

    @Test
    void teammateContextStillAsksForSoftRiskAndUsesExistingApprovalRoute() {
        PolicyPermissionChecker checker = checker("dev", BuiltInPolicies.policy("dev"));

        PermissionDecision decision = checker.check(request(
                "bash",
                args().put("command", "printf hi"),
                Map.of("agent.team.role", "backend")
        ));

        assertThat(decision.outcome()).isEqualTo(PermissionDecision.Outcome.ASK_USER);
    }

    private PolicyPermissionChecker checker(String policyName, PolicyConfig policy) {
        return new PolicyPermissionChecker(policyName, policy, new DefaultPermissionChecker(workspace));
    }

    private static PermissionRequest request(String toolName, ObjectNode arguments) {
        return request(toolName, arguments, Map.of());
    }

    private static PermissionRequest request(String toolName, ObjectNode arguments, Map<String, String> metadata) {
        return new PermissionRequest("call-1", toolName, arguments, metadata);
    }

    private static ObjectNode args() {
        return JsonNodeFactory.instance.objectNode();
    }
}
