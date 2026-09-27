package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AiSettingsService {
    private final AiProviderSettingsRepository repository;
    private final AiRuntimeManager runtimeManager;

    @PostConstruct
    void loadPersistedSettings() {
        repository.findById(AiProviderSettingsEntity.SINGLETON_ID)
                .map(AiProviderSettingsEntity::toSettings)
                .ifPresent(runtimeManager::reload);
    }

    public AiConnectionSettings current() {
        return runtimeManager.current().settings();
    }

    @Transactional
    public AiConnectionSettings update(String host, Integer port, String model) {
        AiConnectionSettings normalized = normalize(host, port, model, Instant.now());
        AiProviderSettingsEntity saved =
                repository.saveAndFlush(new AiProviderSettingsEntity(normalized));
        AiConnectionSettings settings = saved.toSettings();
        runtimeManager.reload(settings);
        return settings;
    }

    public AiRuntimeManager.ConnectionTest test(String host, Integer port, String model) {
        return runtimeManager.testConnection(normalize(host, port, model, null));
    }

    private AiConnectionSettings normalize(
            String host,
            Integer port,
            String model,
            Instant updatedAt) {
        String normalizedHost = host == null ? "" : host.trim();
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        if (normalizedHost.isBlank()
                || normalizedHost.contains("/")
                || normalizedHost.contains("?")
                || normalizedHost.contains("#")
                || normalizedHost.contains("://")
                || normalizedHost.chars().anyMatch(Character::isWhitespace)
                || port == null || port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                    "Enter a valid llama.cpp IP address or hostname and port (1-65535)");
        }

        String authorityHost = normalizedHost.contains(":")
                ? "[" + normalizedHost + "]" : normalizedHost;
        URI uri;
        try {
            uri = URI.create("http://" + authorityHost + ":" + port);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Enter a valid llama.cpp IP address or hostname", exception);
        }
        if (uri.getHost() == null || !normalizedHost.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException(
                    "Enter a valid llama.cpp IP address or hostname");
        }

        String normalizedModel = model == null ? "" : model.trim();
        if (normalizedModel.isBlank() || normalizedModel.length() > 255) {
            throw new IllegalArgumentException("Enter a valid llama.cpp model name");
        }
        return new AiConnectionSettings(normalizedHost, port, normalizedModel, updatedAt);
    }
}
