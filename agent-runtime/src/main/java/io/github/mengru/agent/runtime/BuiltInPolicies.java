package io.github.mengru.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

final class BuiltInPolicies {

    static final String DEV = "dev";
    static final String READONLY = "readonly";

    private BuiltInPolicies() {
    }

    static boolean isBuiltIn(String name) {
        return DEV.equals(name) || READONLY.equals(name);
    }

    static PolicyConfig policy(String name) {
        return switch (name) {
            case DEV -> dev();
            case READONLY -> readonly();
            default -> throw new RuntimeProfileException("Unknown built-in policy: " + name);
        };
    }

    private static PolicyConfig dev() {
        return new PolicyConfig("ask", baseToolRules(), Map.of(
                "cron", cronRules(),
                "readonly", readonlyRules()
        ));
    }

    private static PolicyConfig readonly() {
        return new PolicyConfig("ask", baseToolRules(), Map.of(
                "cron", cronRules(),
                "readonly", readonlyRules()
        ));
    }

    private static Map<String, String> baseToolRules() {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        rules.put("subagent", "allow");
        rules.put("todo_write", "allow");
        rules.put("load_skill", "allow");
        rules.put("read_file", "allow");
        rules.put("glob", "allow");
        rules.put("bash", "ask");
        rules.put("write_file", "ask");
        rules.put("edit_file", "ask");
        rules.put("mcp__*", "ask");
        rules.put("schedule_task", "allow");
        rules.put("list_scheduled_tasks", "allow");
        rules.put("cancel_scheduled_task", "allow");
        rules.put("create_task", "allow");
        rules.put("list_tasks", "allow");
        rules.put("get_task", "allow");
        rules.put("can_start", "allow");
        rules.put("claim_task", "allow");
        rules.put("complete_task", "allow");
        rules.put("spawn_teammate", "allow");
        rules.put("send_message", "allow");
        rules.put("list_teammates", "allow");
        return rules;
    }

    private static Map<String, String> cronRules() {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        rules.put("bash", "deny");
        rules.put("write_file", "deny");
        rules.put("edit_file", "deny");
        rules.put("mcp__*", "deny");
        return rules;
    }

    private static Map<String, String> readonlyRules() {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        rules.put("subagent", "deny");
        rules.put("bash", "deny");
        rules.put("write_file", "deny");
        rules.put("edit_file", "deny");
        rules.put("mcp__*", "deny");
        rules.put("create_task", "deny");
        rules.put("claim_task", "deny");
        rules.put("complete_task", "deny");
        rules.put("schedule_task", "deny");
        rules.put("cancel_scheduled_task", "deny");
        rules.put("spawn_teammate", "deny");
        rules.put("send_message", "deny");
        return rules;
    }
}
