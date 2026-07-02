package io.github.mengru.agent.core.recovery;

import java.time.Duration;

@FunctionalInterface
public interface RetrySleeper {

    void sleep(Duration duration) throws InterruptedException;

    static RetrySleeper threadSleep() {
        return duration -> Thread.sleep(duration.toMillis());
    }

    static RetrySleeper noSleep() {
        return duration -> {
        };
    }
}
