package dev.margintrace.margin_attribution_backend.analysis.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Calls llama-server's OpenAI-compatible chat endpoint; classification stays in Java. */
@Component
public class LocalLlamaClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public LocalLlamaClient(ObjectMapper mapper,
                            @Value("${analysis.llama.base-url:http://127.0.0.1:8081}") String baseUrl,
                            @Value("${analysis.llama.model:local}") String model) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/completions");
        this.model = model;
    }

    public Result explain(String category, List<String> evidence) {
        try {
            String system = "You are a manufacturing cost analysis assistant. Write a concise, 2-3 sentence analysis report summary in English only. "
                    + "Use only the supplied calculated classification and evidence. Do not change the classification, invent work orders, "
                    + "process faults or figures, or present an inference as confirmed. Keep unit costs separate from total cost differences. "
                    + "If the classification is Unit cost change, explicitly state that overuse, material substitution and BOM change are not established.";
            String facts = "Classification: " + category + "\nEvidence:\n" + String.join("\n", evidence);
            String body = mapper.writeValueAsString(Map.of(
                    "model", model, "stream", false, "temperature", 0.1, "max_tokens", 384,
                    "messages", List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", facts))));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 15; attempt++) {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 503 || !response.body().contains("Loading model") || attempt == 14) {
                    break;
                }
                Thread.sleep(2_000);
            }
            if (response.statusCode() != 200) {
                String detail = "";
                try {
                    JsonNode error = mapper.readTree(response.body()).get("error");
                    JsonNode message = error == null ? null : error.get("message");
                    if (message != null) detail = ": " + message.asText();
                } catch (Exception ignored) {
                    // The HTTP status still provides a useful error when the body is not JSON.
                }
                return new Result(null, false, "llama.cpp returned HTTP " + response.statusCode() + detail);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return new Result(null, false, "llama.cpp returned no usable content");
            }
            JsonNode content = choices.get(0).get("message").get("content");
            String summary = content == null ? "" : content.asText().trim();
            return summary.isEmpty()
                    ? new Result(null, false, "llama.cpp returned no usable content")
                    : new Result(summary, true, null);
        } catch (ConnectException exception) {
            return new Result(null, false, "Cannot connect to llama.cpp at " + endpoint.getHost() + ":" + endpoint.getPort());
        } catch (HttpTimeoutException exception) {
            return new Result(null, false, "llama.cpp timed out; check whether the model is still loading");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(null, false, "llama.cpp request was interrupted");
        } catch (Exception exception) {
            return new Result(null, false, "llama.cpp request failed (" + exception.getClass().getSimpleName() + ")");
        }
    }

    public record Result(String summary, boolean generated, String message) { }
}
