package io.github.mengru.agent.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class ModelException extends RuntimeException {

    private final ModelErrorCode code;
    private final int statusCode;
    private final Duration retryAfter;
    private final String partialContent;

    public ModelException(ModelErrorCode code, String message) {
        this(code, message, null, -1, null, "");
    }

    public ModelException(ModelErrorCode code, String message, Throwable cause) {
        this(code, message, cause, -1, null, "");
    }

    public ModelException(
            ModelErrorCode code,
            String message,
            Throwable cause,
            int statusCode,
            Duration retryAfter,
            String partialContent
    ) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
        this.partialContent = partialContent == null ? "" : partialContent;
    }

    public ModelErrorCode code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public String partialContent() {
        return partialContent;
    }
}
