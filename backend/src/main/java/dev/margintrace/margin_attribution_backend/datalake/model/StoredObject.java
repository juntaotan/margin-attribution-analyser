package dev.margintrace.margin_attribution_backend.datalake.model;

/**
 * Location, entity tag and size of an object written to MinIO.
 */
public record StoredObject(String bucket, String objectKey, String etag, long size) {}
