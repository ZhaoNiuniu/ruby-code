package io.github.mengru.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyConfigTest {

    @Test
    void exactRuleWinsBeforePrefixRule() {
        PolicyConfig policy = new PolicyConfig(
                "ask",
                Map.of(
                        "mcp__*", "deny",
                        "mcp__demo__echo", "allow"
                ),
                Map.of()
        );

        assertThat(policy.actionFor("mcp__demo__echo", Set.of())).isEqualTo(PolicyAction.ALLOW);
        assertThat(policy.actionFor("mcp__other__echo", Set.of())).isEqualTo(PolicyAction.DENY);
    }

    @Test
    void unmatchedToolUsesDefaultAsk() {
        PolicyConfig policy = new PolicyConfig("ask", Map.of(), Map.of());

        assertThat(policy.actionFor("future_tool", Set.of())).isEqualTo(PolicyAction.ASK_USER);
    }

    @Test
    void contextsUseStrictestAction() {
        PolicyConfig policy = new PolicyConfig(
                "ask",
                Map.of("bash", "allow"),
                Map.of(
                        "teammate", Map.of("bash", "ask"),
                        "cron", Map.of("bash", "deny")
                )
        );

        assertThat(policy.actionFor("bash", Set.of("teammate"))).isEqualTo(PolicyAction.ASK_USER);
        assertThat(policy.actionFor("bash", Set.of("teammate", "cron"))).isEqualTo(PolicyAction.DENY);
    }

    @Test
    void invalidActionAndPatternFailFast() {
        assertThatThrownBy(() -> new PolicyConfig("explode", Map.of(), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("Unknown policy action");
        assertThatThrownBy(() -> new PolicyConfig("ask", Map.of("*", "allow"), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("wildcard prefix");
        assertThatThrownBy(() -> new PolicyConfig("ask", Map.of("mc*p", "allow"), Map.of()))
                .isInstanceOf(RuntimeProfileException.class)
                .hasMessageContaining("trailing prefix wildcard");
    }
}
