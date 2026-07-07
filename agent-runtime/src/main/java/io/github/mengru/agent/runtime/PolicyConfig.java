package io.github.mengru.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public record PolicyConfig(
        String defaultAction,
        Map<String, String> tools,
        Map<String, Map<String, String>> contexts
) {

    private static final Pattern STABLE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    public PolicyConfig {
        defaultAction = PolicyAction.parse(defaultAction == null || defaultAction.isBlank() ? "ask" : defaultAction).value();
        tools = immutableRuleMap(tools, "tools");
        contexts = immutableContexts(contexts);
    }

    public PolicyAction defaultPolicyAction() {
        return PolicyAction.parse(defaultAction);
    }

    public PolicyAction actionFor(String toolName, Set<String> contextNames) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        PolicyAction action = matchingAction(tools, toolName).orElse(defaultPolicyAction());
        for (String contextName : contextNames == null ? Set.<String>of() : contextNames) {
            if (contextName == null || contextName.isBlank()) {
                continue;
            }
            Map<String, String> contextRules = contexts.get(contextName.strip());
            if (contextRules == null) {
                continue;
            }
            Optional<PolicyAction> contextAction = matchingAction(contextRules, toolName);
            if (contextAction.isPresent()) {
                action = PolicyAction.stricter(action, contextAction.get());
            }
        }
        return action;
    }

    public Map<String, Object> sanitizedMap() {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("defaultAction", defaultAction);
        summary.put("tools", tools);
        summary.put("contexts", contexts);
        return summary;
    }

    private static Optional<PolicyAction> matchingAction(Map<String, String> rules, String toolName) {
        String exact = rules.get(toolName);
        if (exact != null) {
            return Optional.of(PolicyAction.parse(exact));
        }
        String bestAction = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            String pattern = entry.getKey();
            if (!pattern.endsWith("*")) {
                continue;
            }
            String prefix = pattern.substring(0, pattern.length() - 1);
            if (toolName.startsWith(prefix) && prefix.length() > bestLength) {
                bestLength = prefix.length();
                bestAction = entry.getValue();
            }
        }
        return bestAction == null ? Optional.empty() : Optional.of(PolicyAction.parse(bestAction));
    }

    private static Map<String, String> immutableRuleMap(Map<String, String> rules, String path) {
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            String pattern = normalizePattern(entry.getKey(), path);
            String action = PolicyAction.parse(entry.getValue()).value();
            normalized.put(pattern, action);
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static Map<String, Map<String, String>> immutableContexts(Map<String, Map<String, String>> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : contexts.entrySet()) {
            String contextName = normalizeStableName(entry.getKey(), "contexts");
            normalized.put(contextName, immutableRuleMap(entry.getValue(), "contexts." + contextName));
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String normalizePattern(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new RuntimeProfileException("Policy rule pattern must not be blank: " + path);
        }
        String pattern = value.strip();
        int wildcard = pattern.indexOf('*');
        if (wildcard < 0) {
            return normalizeStableName(pattern, path);
        }
        if (wildcard != pattern.length() - 1 || pattern.indexOf('*', wildcard + 1) >= 0) {
            throw new RuntimeProfileException("Policy rule only supports trailing prefix wildcard: " + pattern);
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        if (prefix.isBlank()) {
            throw new RuntimeProfileException("Policy rule wildcard prefix must not be blank: " + pattern);
        }
        normalizeStableName(prefix, path);
        return pattern;
    }

    private static String normalizeStableName(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new RuntimeProfileException("Policy name must not be blank: " + path);
        }
        String normalized = value.strip();
        if (!STABLE_NAME.matcher(normalized).matches()) {
            throw new RuntimeProfileException("Policy name must match [A-Za-z0-9_-]+: " + normalized);
        }
        return normalized;
    }
}
