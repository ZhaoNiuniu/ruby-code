package io.github.mengru.agent.core.permission;

@FunctionalInterface
public interface UserApprover {

    boolean approve(PermissionRequest request, PermissionDecision decision);

    static UserApprover denyAll() {
        return (request, decision) -> false;
    }

    static UserApprover allowAll() {
        return (request, decision) -> true;
    }
}
