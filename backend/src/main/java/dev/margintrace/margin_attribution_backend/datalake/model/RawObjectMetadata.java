package dev.margintrace.margin_attribution_backend.datalake.model;

public record RawObjectMetadata(
        String objectKey,
        long size
) {
}
