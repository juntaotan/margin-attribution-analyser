package dev.margintrace.margin_attribution_backend.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProviderSettingsRepository
        extends JpaRepository<AiProviderSettingsEntity, Long> {
}
