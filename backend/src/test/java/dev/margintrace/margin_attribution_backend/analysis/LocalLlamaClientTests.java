package dev.margintrace.margin_attribution_backend.analysis;

import com.sun.net.httpserver.HttpServer;
import dev.margintrace.margin_attribution_backend.analysis.service.LocalLlamaClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLlamaClientTests {
    @Test
    void usesLlamaChatEndpointAndReadsGeneratedSummary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("Unit cost change", "Evidence:", "in English only");
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"Unit cost increased.\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new LocalLlamaClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "local");

            var result = client.explain("Unit cost change", List.of("Quantity is unchanged"));

            assertThat(result.generated()).isTrue();
            assertThat(result.summary()).isEqualTo("Unit cost increased.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesWhileLlamaModelIsLoading() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            boolean loading = calls.getAndIncrement() == 0;
            byte[] response = (loading
                    ? "{\"error\":{\"message\":\"Loading model\"}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"The model is ready.\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(loading ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new LocalLlamaClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "local");

            var result = client.explain("Unit cost change", List.of("Cost increased"));

            assertThat(result.generated()).isTrue();
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
