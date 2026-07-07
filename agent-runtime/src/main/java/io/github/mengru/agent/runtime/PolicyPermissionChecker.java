package io.github.mengru.agent.runtime;

import io.github.mengru.agent.core.permission.DefaultPermissionChecker;
import io.github.mengru.agent.core.permission.PermissionChecker;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PolicyPermissionChecker implements PermissionChecker {

    private final String policyName;
    private final PolicyConfig policy;
    private final PermissionChecker safetyChecker;

    public PolicyPermissionChecker(RuntimeSettings settings, Path workspaceRoot) {
        this(
                Objects.requireNonNull(settings, "settings must not be null").policyName(),
                settings.policyConfig(),
                new DefaultPermissionChecker(workspaceRoot)
        );
    }

    public PolicyPermissionChecker(String policyName, PolicyConfig policy, PermissionChecker safetyChecker) {
        this.policyName = policyName == null ? "" : policyName.strip();
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.safetyChecker = Objects.requireNonNull(safetyChecker, "safetyChecker must not be null");
    }

    @Override
    public PermissionDecision check(PermissionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        PermissionDecision safetyDecision = safetyChecker.check(request);
        if (safetyDecision.outcome() == PermissionDecision.Outcome.DENY) {
            return safetyDecision;
        }

        PolicyAction action = policy.actionFor(request.toolName(), contextNames(request));
        return switch (action) {
            case ALLOW -> PermissionDecision.allow();
            case DENY -> PermissionDecision.deny("policy denies tool: " + request.toolName());
            case ASK_USER -> askDecision(request, safetyDecision);
        };
    }

    private PermissionDecision askDecision(PermissionRequest request, PermissionDecision safetyDecision) {
        if (safetyDecision.outcome() == PermissionDecision.Outcome.ASK_USER) {
            return safetyDecision;
        }
        return PermissionDecision.askUser(
                "policy requires approval for tool: " + request.toolName(),
                "tool=" + request.toolName() + ", arguments=" + summarizeArguments(request)
        );
    }

    private Set<String> contextNames(PermissionRequest request) {
        LinkedHashSet<String> contexts = new LinkedHashSet<>();
        if (!policyName.isBlank()) {
            contexts.add(policyName);
        }
        String trigger = request.metadata().getOrDefault("agent.trigger", "").strip();
        if (!trigger.isBlank()) {
            contexts.add(trigger);
        }
        if (!request.metadata().getOrDefault("agent.team.id", "").isBlank() || "team".equals(trigger)) {
            contexts.add("team");
        }
        if (!request.metadata().getOrDefault("agent.team.role", "").isBlank()) {
            contexts.add("teammate");
        }
        return contexts;
    }

    private static String summarizeArguments(PermissionRequest request) {
        String value = request.arguments().toString();
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...[truncated]";
    }
}
