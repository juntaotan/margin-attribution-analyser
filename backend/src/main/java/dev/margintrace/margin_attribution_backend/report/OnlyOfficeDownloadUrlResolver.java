package dev.margintrace.margin_attribution_backend.report;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * Rewrites document download URLs from ONLYOFFICE save callbacks so that the backend downloads through the Document
 * Server's internal address, and rejects URLs from any other origin.
 */
final class OnlyOfficeDownloadUrlResolver {

    private OnlyOfficeDownloadUrlResolver() {}

    /**
     * Resolves a callback download URL to the internal Document Server address.
     *
     * @param publicUrl the Document Server URL used by browsers
     * @param internalUrl the Document Server URL reachable from the backend
     * @param documentUri the download URL from the callback
     * @return the download URL on the internal origin, keeping the original path and query
     * @throws IllegalArgumentException if the URL is not HTTP(S) or its origin is neither the public nor the
     *     internal Document Server origin
     */
    static URI resolve(String publicUrl, String internalUrl, URI documentUri) {
        URI publicUri = URI.create(publicUrl);
        URI internalUri = URI.create(internalUrl);

        if (!isHttpUri(documentUri)
                || (!hasSameOrigin(documentUri, publicUri) && !hasSameOrigin(documentUri, internalUri))) {
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
                    null);
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
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
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
        String normalizedBase = basePath == null || "/".equals(basePath) ? "" : basePath.replaceAll("/+$", "");
        String normalizedDocument = documentPath == null || documentPath.isBlank()
                ? "/"
                : (documentPath.startsWith("/") ? documentPath : "/" + documentPath);
        return normalizedBase + normalizedDocument;
    }
}
