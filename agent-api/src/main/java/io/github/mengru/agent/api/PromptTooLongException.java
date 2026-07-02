package io.github.mengru.agent.api;

public class PromptTooLongException extends ModelException {

    public PromptTooLongException(String message) {
        super(ModelErrorCode.PROMPT_TOO_LONG, message);
    }

    public PromptTooLongException(String message, Throwable cause) {
        super(ModelErrorCode.PROMPT_TOO_LONG, message, cause);
    }
}
