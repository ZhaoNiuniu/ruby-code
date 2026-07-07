package io.github.mengru.agent.runtime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record RuntimeSettings(
        String profileName,
        String profileSource,
        String provider,
        String providerSource,
        String model,
        String modelSource,
        String baseUrl,
        String baseUrlSource,
        String system,
        boolean localTools,
        boolean subagentTools,
        boolean teamTools,
        boolean schedulerTools,
        boolean mcpTools,
        Path mcpConfigPath,
        boolean persistentMemory,
        boolean sessionMemory,
        boolean contextCompressionEnabled,
        int contextWindowTokens,
        int maxOutputTokens,
        int reservedTokens,
        boolean errorRecoveryEnabled,
        int modelRetryAttempts,
        int generationMaxOutputTokens,
        int recoveryMaxOutputTokens,
        boolean traceEnabled,
        String traceSink,
        String policyName,
        String policySource,
        PolicyConfig policyConfig
) {

    public RuntimeSettings {
        Objects.requireNonNull(profileName, "profileName must not be null");
        Objects.requireNonNull(profileSource, "profileSource must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerSource, "providerSource must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(baseUrlSource, "baseUrlSource must not be null");
        Objects.requireNonNull(traceSink, "traceSink must not be null");
        Objects.requireNonNull(policyName, "policyName must not be null");
        Objects.requireNonNull(policySource, "policySource must not be null");
        Objects.requireNonNull(policyConfig, "policyConfig must not be null");
    }

    public Map<String, Object> sanitizedMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("profile", profileName);
        map.put("profileSource", profileSource);
        map.put("provider", valueWithSource(provider, providerSource));
        map.put("model", valueWithSource(model == null ? "" : model, modelSource));
        map.put("baseUrl", valueWithSource(baseUrl, baseUrlSource));
        map.put("system", system == null || system.isBlank() ? "" : "[configured]");
        LinkedHashMap<String, Object> policy = new LinkedHashMap<>();
        policy.put("name", policyName);
        policy.put("source", policySource);
        policy.putAll(policyConfig.sanitizedMap());
        map.put("policy", policy);

        LinkedHashMap<String, Object> tools = new LinkedHashMap<>();
        tools.put("local", localTools);
        tools.put("subagent", subagentTools);
        tools.put("team", teamTools);
        tools.put("scheduler", schedulerTools);
        tools.put("mcp", mcpTools);
        tools.put("mcpConfig", mcpConfigPath == null ? "" : mcpConfigPath.toString());
        map.put("tools", tools);

        map.put("memory", Map.of(
                "persistent", persistentMemory,
                "session", sessionMemory
        ));
        map.put("contextCompression", Map.of(
                "enabled", contextCompressionEnabled,
                "contextWindowTokens", contextWindowTokens,
                "maxOutputTokens", maxOutputTokens,
                "reservedTokens", reservedTokens
        ));
        map.put("errorRecovery", Map.of(
                "enabled", errorRecoveryEnabled,
                "modelRetryAttempts", modelRetryAttempts,
                "generationMaxOutputTokens", generationMaxOutputTokens,
                "recoveryMaxOutputTokens", recoveryMaxOutputTokens
        ));
        map.put("trace", Map.of(
                "enabled", traceEnabled,
                "sink", traceSink
        ));
        return map;
    }

    private static Map<String, String> valueWithSource(String value, String source) {
        return Map.of("value", value, "source", source == null ? "" : source);
    }
}
