package dev.margintrace.margin_attribution_backend.analysis.ai;

import com.sun.net.httpserver.HttpServer;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AiServiceConfigurationTests {

    @Test
    void delegatesAllThreeServicesAndReloadsRuntimeWithoutRestart() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = aiServer(requests, "Unit cost increased.");
        HttpServer replacement = aiServer(new CopyOnWriteArrayList<>(), "Runtime reloaded.");
        server.start();
        replacement.start();

        try {
            AiRuntimeManager runtimeManager = new AiRuntimeManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "local");
            AiServiceConfiguration configuration = new AiServiceConfiguration();
            RootCauseAssistant summaryAssistant =
                    configuration.rootCauseAssistant(runtimeManager);
            var periodAssistant = configuration.reportPeriodAssistant(runtimeManager);
            var blueprintAssistant = configuration.reportBlueprintAssistant(runtimeManager);

            String summary = summaryAssistant
                    .summarize("Unit cost change", "Unit cost increased.");
            var period = periodAssistant
                    .extract("1. [[TARGET tag=x]] January 2026");
            ReportBlueprint blueprint = blueprintAssistant
                    .generate("Revenue for Duration: January 2026");

            assertThat(summary).isEqualTo("Unit cost increased.");
            assertThat(period.found()).isTrue();
            assertThat(period.duration()).isEqualTo("January 2026");
            assertThat(blueprint.sourceTable()).isEqualTo("sales_order");
            assertThat(blueprint.inputFields()).singleElement()
                    .extracting(field -> field.field())
                    .isEqualTo("product_total_price");
            assertThat(requests).hasSize(3);
            assertThat(requests.get(0)).contains("\"messages\"", "Unit cost change");
            assertThat(requests.get(1)).contains("\"response_format\"", "\"json_schema\"");
            assertThat(requests.get(2)).contains("\"response_format\"", "\"json_schema\"");

            runtimeManager.reload(new AiConnectionSettings(
                    "127.0.0.1", replacement.getAddress().getPort(), "local", null));

            assertThat(summaryAssistant.summarize("Any", "Any"))
                    .isEqualTo("Runtime reloaded.");
        } finally {
            server.stop(0);
            replacement.stop(0);
        }
    }

    @Test
    void healthCheckRunsFromBackendWithoutSavingSettings() throws Exception {
        HttpServer server = aiServer(new CopyOnWriteArrayList<>(), "unused");
        server.start();
        try {
            AiRuntimeManager runtimeManager =
                    new AiRuntimeManager("http://127.0.0.1:1", "local");
            AiConnectionSettings candidate = new AiConnectionSettings(
                    "127.0.0.1", server.getAddress().getPort(), "local", null);

            AiRuntimeManager.ConnectionTest result =
                    runtimeManager.testConnection(candidate);

            assertThat(result.reachable()).isTrue();
            assertThat(result.modelReady()).isTrue();
            assertThat(result.message()).contains("model is ready");
            assertThat(runtimeManager.current().settings().port()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer aiServer(List<String> requests, String summary) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            String content;
            if (request.contains("Classification:")) {
                content = summary;
            } else if (request.contains("Document sentences:")) {
                content = """
                        {"found":true,"duration":"January 2026"}""";
            } else {
                content = """
                        {"sourceTable":"sales_order","sourceTableLabel":"sales_order (Sales orders)",
                         "filterConditions":["date is within January 2026"],
                         "inputFields":[{"field":"product_total_price","label":"Revenue",
                         "description":"Sales line total"}],"formula":"SUM(product_total_price)",
                         "formulaDescription":"Sum sales revenue","format":"currency",
                         "explanation":"Uses recorded sales lines"}""";
            }
            String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                    + jsonString(content) + "},\"finish_reason\":\"stop\"}],\"model\":\"local\"}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
