package io.github.mengru.agent.provider.openai;

public final class OpenAiCompatibleException extends RuntimeException {

    public OpenAiCompatibleException(String message) {
        super(message);
    }

    public OpenAiCompatibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
