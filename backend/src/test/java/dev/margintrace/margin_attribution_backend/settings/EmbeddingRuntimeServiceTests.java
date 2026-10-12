package dev.margintrace.margin_attribution_backend.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingRuntimeServiceTests {
    private static final String MODEL = "sentence-transformers/all-MiniLM-L6-v2";
    private static final String REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41";

    @TempDir
    Path dir;

    /** Replace the real installer with a shell script that follows the same output and marker contract. */
    private EmbeddingRuntimeService fake(String script) {
        return new EmbeddingRuntimeService(dir.toString(), MODEL, REVISION) {
            @Override
            protected List<String> command(String variant) {
                return List.of("sh", "-c", script, "install", variant, dir.toString());
            }
        };
    }

    private EmbeddingRuntimeService.Status await(EmbeddingRuntimeService service) throws InterruptedException {
        for (int i = 0; i < 100 && service.status().state().equals("INSTALLING"); i++) Thread.sleep(50);
        return service.status();
    }

    @Test
    void successfulInstallationIsRecognisedFromTheMarker() throws Exception {
        var service = fake("echo 'STAGE Downloading'; printf '{\"variant\":\"%s\",\"modelId\":\"" + MODEL + "\",\"revision\":\""
                + REVISION + "\",\"cudaAvailable\":false}' \"$1\" > \"$2/installed.json\"");
        assertThat(service.status().state()).isEqualTo("NOT_INSTALLED");
        assertThat(service.install("cuda").state()).isEqualTo("INSTALLING");
        var status = await(service);
        assertThat(status.state()).isEqualTo("READY");
        assertThat(status.variant()).isEqualTo("cuda");
        assertThat(status.cudaAvailable()).isFalse();
        assertThat(new EmbeddingRuntimeService(dir.toString(), MODEL, REVISION).status().state()).isEqualTo("READY");
        assertThat(new EmbeddingRuntimeService(dir.toString(), MODEL, "0".repeat(40)).status().state()).isEqualTo("NOT_INSTALLED");
    }

    @Test
    void failureKeepsStageAndOutput() throws Exception {
        var status = await(withInstall(fake("echo 'STAGE Installing PyTorch (cpu)'; echo 'No matching distribution'; exit 1"), "cpu"));
        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.stage()).isEqualTo("Installing PyTorch (cpu)");
        assertThat(status.message()).contains("No matching distribution");
    }

    @Test
    void rejectsUnknownVariantsAndConcurrentInstallations() throws Exception {
        var service = fake("sleep 1");
        assertThatThrownBy(() -> service.install("rocm")).isInstanceOf(IllegalArgumentException.class);
        service.install("cpu");
        assertThatThrownBy(() -> service.install("cpu")).isInstanceOf(IllegalStateException.class);
        assertThat(await(service).state()).isEqualTo("NOT_INSTALLED");
    }

    private static EmbeddingRuntimeService withInstall(EmbeddingRuntimeService service, String variant) {
        service.install(variant);
        return service;
    }
}
