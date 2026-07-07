package io.github.mengru.agent.runtime;

public final class RuntimeProfileException extends RuntimeException {

    public RuntimeProfileException(String message) {
        super(message);
    }

    public RuntimeProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
