package io.github.mengru.agent.core.permission;

@FunctionalInterface
public interface PermissionChecker {

    PermissionDecision check(PermissionRequest request);
}
