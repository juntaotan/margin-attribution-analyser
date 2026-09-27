package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OnlyOfficeDownloadUrlResolverTest {

    @Test
    void rewritesTheConfiguredPublicOriginToTheDockerInternalOrigin() {
        URI resolved = OnlyOfficeDownloadUrlResolver.resolve(
                "http://localhost:18082",
                "http://onlyoffice",
                URI.create("http://localhost:18082/cache/files/output.docx?md5=abc&expires=123")
        );

        assertThat(resolved).isEqualTo(
                URI.create("http://onlyoffice/cache/files/output.docx?md5=abc&expires=123"));
    }

    @Test
    void keepsAnAlreadyInternalDownloadUrlOnTheInternalOrigin() {
        URI resolved = OnlyOfficeDownloadUrlResolver.resolve(
                "http://localhost:18082",
                "http://onlyoffice",
                URI.create("http://onlyoffice/cache/files/output.docx?md5=abc")
        );

        assertThat(resolved).isEqualTo(
                URI.create("http://onlyoffice/cache/files/output.docx?md5=abc"));
    }

    @Test
    void rejectsDownloadsFromAnyOtherOrigin() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                OnlyOfficeDownloadUrlResolver.resolve(
                        "http://localhost:18082",
                        "http://onlyoffice",
                        URI.create("http://attacker.invalid/cache/files/output.docx")
                ));
    }
}
