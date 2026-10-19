package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The saved llama.cpp connection. The table holds a single row with ID {@link #SINGLETON_ID}.
 */
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

    protected AiProviderSettingsEntity() {}

    /**
     * @param settings the settings to save in the singleton row
     */
    public AiProviderSettingsEntity(AiConnectionSettings settings) {
        this.id = SINGLETON_ID;
        this.host = settings.host();
        this.port = settings.port();
        this.modelName = settings.model();
        this.updatedAt = settings.updatedAt();
    }

    /**
     * @return the saved settings
     */
    public AiConnectionSettings toSettings() {
        return new AiConnectionSettings(host, port, modelName, updatedAt);
    }
}
