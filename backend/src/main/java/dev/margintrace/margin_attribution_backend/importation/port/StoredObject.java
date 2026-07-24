package dev.margintrace.margin_attribution_backend.importation.port;

public record StoredObject(
        String bucket,
        String objectKey,
        String etag,
        long size
) {
}
