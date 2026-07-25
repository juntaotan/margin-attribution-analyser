package dev.margintrace.margin_attribution_backend.datalake.model;

public record StoredObject(
        String bucket,
        String objectKey,
        String etag,
        long size
) {
}
