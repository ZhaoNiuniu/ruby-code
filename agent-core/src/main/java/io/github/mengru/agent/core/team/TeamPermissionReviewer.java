package io.github.mengru.agent.core.team;

@FunctionalInterface
public interface TeamPermissionReviewer {

    TeamPermissionReviewDecision review(TeamPermissionReviewRequest request);
}
