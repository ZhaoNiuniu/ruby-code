package io.github.mengru.agent.core.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronExpressionTest {

    @Test
    void supportsSixFieldBasicSyntax() {
        ZoneId zone = ZoneId.of("UTC");

        assertThat(CronExpression.parse("*/5 * * * * *").nextAfter(
                Instant.parse("2026-07-01T00:00:00Z"),
                zone
        )).isEqualTo(Instant.parse("2026-07-01T00:00:05Z"));

        assertThat(CronExpression.parse("1,3 0-2 4 * * *").nextAfter(
                Instant.parse("2026-07-01T04:00:01Z"),
                zone
        )).isEqualTo(Instant.parse("2026-07-01T04:00:03Z"));
    }

    @Test
    void rejectsInvalidOrUnsupportedSyntax() {
        assertThatThrownBy(() -> CronExpression.parse("* * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6 fields");
        assertThatThrownBy(() -> CronExpression.parse("60 * * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> CronExpression.parse("* * * ? * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
    }
}
