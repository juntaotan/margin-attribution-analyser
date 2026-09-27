package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import dev.margintrace.margin_attribution_backend.report.ai.ReportPeriodAssistant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AiRuntimeManager {
    private final AtomicReference<AiRuntime> current;
    private final HttpClient healthClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public AiRuntimeManager(
            @Value("${analysis.llama.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${analysis.llama.model:local}") String model) {
        URI uri = URI.create(baseUrl.replaceAll("/+$", ""));
        int port = uri.getPort() >= 0
                ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        this.current = new AtomicReference<>(build(
                new AiConnectionSettings(uri.getHost(), port, model, null)));
    }

    public AiRuntime current() {
        return current.get();
    }

    public void reload(AiConnectionSettings settings) {
        AiRuntime replacement = build(settings);
        current.set(replacement);
    }

    public ConnectionTest testConnection(AiConnectionSettings settings) {
        long started = System.nanoTime();
        URI healthEndpoint = URI.create(settings.baseUrl() + "/health");
        try {
            HttpRequest request = HttpRequest.newBuilder(healthEndpoint)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    healthClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = elapsedMillis(started);
            if (response.statusCode() == 200) {
                return new ConnectionTest(true, true, latency,
                        "Connected to llama.cpp; the model is ready");
            }
            if (response.statusCode() == 503 && response.body().contains("Loading model")) {
                return new ConnectionTest(true, false, latency,
                        "Connected to llama.cpp; the model is still loading");
            }
            return new ConnectionTest(true, false, latency,
                    "llama.cpp health check returned HTTP " + response.statusCode());
        } catch (ConnectException exception) {
            return new ConnectionTest(false, false, elapsedMillis(started),
                    "Cannot connect to llama.cpp at " + settings.host() + ":" + settings.port());
        } catch (java.net.http.HttpTimeoutException exception) {
            return new ConnectionTest(false, false, elapsedMillis(started),
                    "Connection to llama.cpp timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ConnectionTest(false, false, elapsedMillis(started),
                    "llama.cpp health check was interrupted");
        } catch (Exception exception) {
            return new ConnectionTest(false, false, elapsedMillis(started),
                    "llama.cpp health check failed (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private AiRuntime build(AiConnectionSettings settings) {
        RootCauseAssistant rootCause = AiServices.create(
                RootCauseAssistant.class, chatModel(settings, 384, false));
        ReportPeriodAssistant period = AiServices.create(
                ReportPeriodAssistant.class, chatModel(settings, 128, true));
        ReportBlueprintAssistant blueprint = AiServices.create(
                ReportBlueprintAssistant.class, chatModel(settings, 900, true));
        return new AiRuntime(settings, rootCause, period, blueprint);
    }

    private ChatModel chatModel(
            AiConnectionSettings settings,
            int maxOutputTokens,
            boolean structuredOutput) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl() + "/v1")
                .apiKey("no-key")
                .modelName(settings.model())
                .temperature(0.1)
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(0);
        if (structuredOutput) {
            builder.supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                    .strictJsonSchema(true);
        }
        return new LlamaLoadingRetryChatModel(builder.build());
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    public record AiRuntime(
            AiConnectionSettings settings,
            RootCauseAssistant rootCauseAssistant,
            ReportPeriodAssistant reportPeriodAssistant,
            ReportBlueprintAssistant reportBlueprintAssistant) {
    }

    public record ConnectionTest(
            boolean reachable,
            boolean modelReady,
            long latencyMs,
            String message) {
    }
}
