package dev.margintrace.margin_attribution_backend.settings;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the saved llama.cpp connection.
 */
public interface AiProviderSettingsRepository extends JpaRepository<AiProviderSettingsEntity, Long> {}
