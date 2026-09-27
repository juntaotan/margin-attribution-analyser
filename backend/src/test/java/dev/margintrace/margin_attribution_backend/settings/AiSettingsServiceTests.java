package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSettingsServiceTests {
    @Mock private AiProviderSettingsRepository repository;

    @Test
    void updatePersistsAndAtomicallyReloadsRuntime() {
        AiRuntimeManager runtimeManager =
                new AiRuntimeManager("http://127.0.0.1:8081", "local");
        when(repository.saveAndFlush(any(AiProviderSettingsEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AiSettingsService service = new AiSettingsService(repository, runtimeManager);

        AiConnectionSettings saved =
                service.update(" 10.0.0.20 ", 9000, " report-model ");

        assertThat(saved.host()).isEqualTo("10.0.0.20");
        assertThat(saved.port()).isEqualTo(9000);
        assertThat(saved.model()).isEqualTo("report-model");
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(runtimeManager.current().settings()).isEqualTo(saved);
    }

    @Test
    void startupLoadsPersistedSettingsOverEnvironmentDefault() {
        AiRuntimeManager runtimeManager =
                new AiRuntimeManager("http://127.0.0.1:8081", "local");
        AiConnectionSettings persisted = new AiConnectionSettings(
                "host.docker.internal", 8181, "persisted-model", Instant.now());
        when(repository.findById(AiProviderSettingsEntity.SINGLETON_ID))
                .thenReturn(Optional.of(new AiProviderSettingsEntity(persisted)));
        AiSettingsService service = new AiSettingsService(repository, runtimeManager);

        service.loadPersistedSettings();

        assertThat(service.current()).isEqualTo(persisted);
    }
}
