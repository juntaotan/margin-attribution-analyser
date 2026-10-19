package dev.margintrace.margin_attribution_backend.datalake.model;

/**
 * Object key and size of a raw file.
 */
public record RawObjectMetadata(String objectKey, long size) {}
