package dev.margintrace.margin_attribution_backend.report;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

final class OnlyOfficeDownloadUrlResolver {

    private OnlyOfficeDownloadUrlResolver() {
    }

    static URI resolve(String publicUrl, String internalUrl, URI documentUri) {
        URI publicUri = URI.create(publicUrl);
        URI internalUri = URI.create(internalUrl);

        if (!isHttpUri(documentUri)
                || (!hasSameOrigin(documentUri, publicUri)
                && !hasSameOrigin(documentUri, internalUri))) {
            throw new IllegalArgumentException("Unexpected ONLYOFFICE document download origin");
        }

        try {
            return new URI(
                    internalUri.getScheme(),
                    internalUri.getUserInfo(),
                    internalUri.getHost(),
                    internalUri.getPort(),
                    joinPaths(internalUri.getPath(), documentUri.getPath()),
                    documentUri.getQuery(),
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid ONLYOFFICE document download URL", exception);
        }
    }

    private static boolean hasSameOrigin(URI first, URI second) {
        return isHttpUri(second)
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && Objects.equals(normalizeHost(first.getHost()), normalizeHost(second.getHost()))
                && effectivePort(first) == effectivePort(second);
    }

    private static boolean isHttpUri(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static String normalizeHost(String host) {
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String joinPaths(String basePath, String documentPath) {
        String normalizedBase = basePath == null || "/".equals(basePath)
                ? ""
                : basePath.replaceAll("/+$", "");
        String normalizedDocument = documentPath == null || documentPath.isBlank()
                ? "/"
                : (documentPath.startsWith("/") ? documentPath : "/" + documentPath);
        return normalizedBase + normalizedDocument;
    }
}
