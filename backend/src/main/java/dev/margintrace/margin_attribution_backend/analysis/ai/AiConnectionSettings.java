package dev.margintrace.margin_attribution_backend.analysis.ai;

import java.time.Instant;

public record AiConnectionSettings(
        String host,
        int port,
        String model,
        Instant updatedAt) {

    public String baseUrl() {
        String authorityHost = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + authorityHost + ":" + port;
    }
}
