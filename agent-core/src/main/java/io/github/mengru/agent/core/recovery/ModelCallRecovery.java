package io.github.mengru.agent.core.recovery;

import io.github.mengru.agent.api.AgentRecoveryEvent;
import io.github.mengru.agent.api.AgentRequest;
import io.github.mengru.agent.api.ModelErrorCode;
import io.github.mengru.agent.api.ModelException;
import io.github.mengru.agent.api.ModelOptions;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class ModelCallRecovery {

    private final ErrorRecoveryConfig config;
    private final RetrySleeper sleeper;

    public ModelCallRecovery(ErrorRecoveryConfig config) {
        this(config, RetrySleeper.threadSleep());
    }

    public ModelCallRecovery(ErrorRecoveryConfig config, RetrySleeper sleeper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    public static ModelCallRecovery defaults() {
        return new ModelCallRecovery(ErrorRecoveryConfig.defaults());
    }

    public static ModelCallRecovery disabled() {
        return new ModelCallRecovery(ErrorRecoveryConfig.disabled());
    }

    public ErrorRecoveryConfig config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public int transientRetryAttempts() {
        return config.transientRetryAttempts();
    }

    public AgentRequest withRecoveryOutputBudget(AgentRequest request) {
        int nextMax = Math.max(request.modelOptions().maxOutputTokens(), config.recoveryMaxOutputTokens());
        return request.withModelOptions(new ModelOptions(nextMax));
    }

    public AgentRequest continuationRequest(AgentRequest request, String partialContent) {
        String partial = partialContent == null ? "" : partialContent.strip();
        String task = request.task()
                + "\n\nThe previous model response was truncated by the provider. Continue from where it stopped.";
        if (!partial.isBlank()) {
            task += "\n\nPartial response so far:\n" + partial;
        }
        return request.withModelOptions(new ModelOptions(
                Math.max(request.modelOptions().maxOutputTokens(), config.recoveryMaxOutputTokens())
        )).withTask(task);
    }

    public Duration retryDelay(ModelException exception, int attempt) {
        if (exception.retryAfter().isPresent()) {
            return exception.retryAfter().orElseThrow();
        }
        long multiplier = 1L << Math.max(0, attempt - 1);
        Duration delay = config.baseDelay().multipliedBy(multiplier);
        if (!config.jitterEnabled() || delay.isZero()) {
            return delay;
        }
        long jitterMillis = ThreadLocalRandom.current().nextLong(0, Math.max(1, config.baseDelay().toMillis() / 2 + 1));
        return delay.plusMillis(jitterMillis);
    }

    public void sleep(Duration duration) throws InterruptedException {
        sleeper.sleep(duration);
    }

    public AgentRecoveryEvent event(
            ModelErrorCode code,
            String action,
            int attempt,
            boolean recovered,
            int beforeMaxOutputTokens,
            int afterMaxOutputTokens,
            String message
    ) {
        return new AgentRecoveryEvent(
                code,
                action,
                attempt,
                recovered,
                beforeMaxOutputTokens,
                afterMaxOutputTokens,
                message
        );
    }
}
