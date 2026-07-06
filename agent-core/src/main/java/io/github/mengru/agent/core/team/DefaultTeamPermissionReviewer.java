package io.github.mengru.agent.core.team;

import io.github.mengru.agent.core.permission.PermissionDecision;
import io.github.mengru.agent.core.permission.PermissionRequest;
import io.github.mengru.agent.core.task.TaskManager;

import java.util.Set;

public final class DefaultTeamPermissionReviewer implements TeamPermissionReviewer {

    private static final Set<String> KNOWN_SOFT_RISK_TOOLS = Set.of("bash", "write_file", "edit_file");

    @Override
    public TeamPermissionReviewDecision review(TeamPermissionReviewRequest request) {
        PermissionRequest permissionRequest = request.permissionRequest();
        PermissionDecision permissionDecision = request.permissionDecision();
        if (!request.knownTeammate()) {
            return TeamPermissionReviewDecision.deny("unknown teammate: " + request.teammateName());
        }
        if (permissionDecision.outcome() != PermissionDecision.Outcome.ASK_USER) {
            return TeamPermissionReviewDecision.deny("only soft-risk permission requests can be escalated");
        }
        String requester = permissionRequest.metadata().getOrDefault(TaskManager.AGENT_NAME_METADATA_KEY, "");
        if (!requester.isBlank() && !request.teammateName().equals(requester)) {
            return TeamPermissionReviewDecision.deny("permission requester metadata does not match teammate name");
        }
        String toolName = permissionRequest.toolName();
        if (toolName.isBlank()) {
            return TeamPermissionReviewDecision.deny("tool name must not be blank");
        }
        if ("bash".equals(toolName) && permissionRequest.stringArgument("command").isBlank()) {
            return TeamPermissionReviewDecision.deny("bash command must not be blank");
        }
        if (("write_file".equals(toolName) || "edit_file".equals(toolName))
                && permissionRequest.stringArgument("path").isBlank()) {
            return TeamPermissionReviewDecision.deny(toolName + " path must not be blank");
        }
        String reason = KNOWN_SOFT_RISK_TOOLS.contains(toolName)
                ? "Lead review accepted the teammate request as a soft-risk workspace operation"
                : "Lead review found an unknown soft-risk tool and escalated conservatively";
        return TeamPermissionReviewDecision.escalateToUser(reason);
    }
}
