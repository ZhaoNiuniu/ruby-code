package io.github.mengru.agent.api;

public class PromptTooLongException extends RuntimeException {

    public PromptTooLongException(String message) {
        super(message);
    }

    public PromptTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
