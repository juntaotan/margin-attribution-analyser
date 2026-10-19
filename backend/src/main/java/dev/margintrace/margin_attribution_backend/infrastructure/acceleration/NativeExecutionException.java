package dev.margintrace.margin_attribution_backend.infrastructure.acceleration;

/** Native library/runtime/device failures; invalid inputs retain IllegalArgumentException. */
public final class NativeExecutionException extends RuntimeException {
    public NativeExecutionException(String message) {
        super(message);
    }
}
