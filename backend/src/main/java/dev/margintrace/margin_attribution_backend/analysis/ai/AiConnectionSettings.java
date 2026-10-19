package dev.margintrace.margin_attribution_backend.analysis.ai;

import java.time.Instant;

/**
 * Address and model name of the llama.cpp server used by the AI services. {@code updatedAt} is null for settings
 * taken from application properties rather than saved by a user.
 */
public record AiConnectionSettings(String host, int port, String model, Instant updatedAt) {

    /**
     * @return the server's HTTP base URL, with IPv6 hosts in brackets
     */
    public String baseUrl() {
        String authorityHost = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + authorityHost + ":" + port;
    }
}
