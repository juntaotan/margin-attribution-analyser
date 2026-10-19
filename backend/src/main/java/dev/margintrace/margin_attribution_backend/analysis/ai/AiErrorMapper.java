package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.langchain4j.exception.HttpException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns exceptions from llama.cpp requests into short messages that can be shown to users, without exposing stack
 * traces or response bodies.
 */
@Component
public class AiErrorMapper {
    private final URI endpoint;

    /**
     * @param baseUrl the configured llama.cpp base URL, used to name the endpoint in connection errors
     */
    public AiErrorMapper(@Value("${analysis.llama.base-url:http://127.0.0.1:8081}") String baseUrl) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", ""));
    }

    /**
     * @param exception the failure, examined through its cause chain
     * @return a message for interruption, connection failure, timeout or HTTP error status, or a generic message
     *     naming the exception type
     */
    public String message(Throwable exception) {
        if (findCause(exception, InterruptedException.class) != null) {
            return "llama.cpp request was interrupted";
        }
        if (findCause(exception, ConnectException.class) != null) {
            return "Cannot connect to llama.cpp at " + endpoint.getHost() + ":" + effectivePort();
        }
        if (findCause(exception, HttpTimeoutException.class) != null) {
            return "llama.cpp timed out; check whether the model is still loading";
        }
        HttpException http = findCause(exception, HttpException.class);
        if (http != null) {
            return "llama.cpp returned HTTP " + http.statusCode();
        }
        return "llama.cpp request failed (" + exception.getClass().getSimpleName() + ")";
    }

    private int effectivePort() {
        if (endpoint.getPort() >= 0) return endpoint.getPort();
        return "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }
}
