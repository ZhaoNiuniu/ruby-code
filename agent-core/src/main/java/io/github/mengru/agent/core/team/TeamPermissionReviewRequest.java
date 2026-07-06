package io.github.mengru.agent.core.team;

import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;

import java.util.Objects;

public record TeamPermissionReviewRequest(
        String teammateName,
        boolean knownTeammate,
        PermissionRequest permissionRequest,
        PermissionDecision permissionDecision
) {

    public TeamPermissionReviewRequest {
        teammateName = MessageBus.validateAgentName(teammateName);
        Objects.requireNonNull(permissionRequest, "permissionRequest must not be null");
        Objects.requireNonNull(permissionDecision, "permissionDecision must not be null");
    }
}
