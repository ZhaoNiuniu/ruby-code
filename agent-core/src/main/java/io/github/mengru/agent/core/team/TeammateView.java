package io.github.mengru.agent.core.team;

import java.time.Instant;

public record TeammateView(
        String name,
        String role,
        TeammateStatus status,
        Instant startedAt,
        String lastMessage
) {
}
