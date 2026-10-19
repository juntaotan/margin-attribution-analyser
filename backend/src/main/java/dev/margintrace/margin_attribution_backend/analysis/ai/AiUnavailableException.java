package dev.margintrace.margin_attribution_backend.analysis.ai;

/**
 * Thrown when an AI service cannot be reached or returns an invalid answer. Controllers report it as HTTP 503.
 */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiUnavailableException(String message) {
        super(message);
    }
}
