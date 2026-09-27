package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ai_provider_settings")
public class AiProviderSettingsEntity {
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiProviderSettingsEntity() {
    }

    public AiProviderSettingsEntity(AiConnectionSettings settings) {
        this.id = SINGLETON_ID;
        this.host = settings.host();
        this.port = settings.port();
        this.modelName = settings.model();
        this.updatedAt = settings.updatedAt();
    }

    public AiConnectionSettings toSettings() {
        return new AiConnectionSettings(host, port, modelName, updatedAt);
    }
}
