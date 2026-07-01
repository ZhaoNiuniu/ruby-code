package io.github.mengru.agent.core.hook;

import io.github.mengru.agent.core.permission.PermissionChecker;
import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.permission.UserApprover;

import java.util.Objects;

public final class PreToolUsePermissionHook implements AgentHook<PreToolUseContext> {

    private final PermissionChecker permissionChecker;
    private final UserApprover userApprover;

    public PreToolUsePermissionHook(PermissionChecker permissionChecker, UserApprover userApprover) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker must not be null");
        this.userApprover = Objects.requireNonNull(userApprover, "userApprover must not be null");
    }

    @Override
    public HookResult<PreToolUseContext> apply(PreToolUseContext context) {
        PermissionRequest request = new PermissionRequest(
                context.toolCall().toolCallId(),
                context.toolRequest().toolName(),
                context.toolRequest().arguments(),
                context.toolRequest().metadata()
        );
        PermissionDecision decision = permissionChecker.check(request);
        if (decision.outcome() == PermissionDecision.Outcome.ALLOW) {
            return HookResult.continueWith(context);
        }
        if (decision.outcome() == PermissionDecision.Outcome.DENY) {
            return HookResult.block(decision.reason(), context);
        }
        boolean approved = userApprover.approve(request, decision);
        if (!approved) {
            return HookResult.block("user rejected approval: " + decision.reason(), context);
        }
        return HookResult.continueWith(context);
    }
}
