package dev.margintrace.margin_attribution_backend.algorithm.execution;

/** Requested and effective execution are reported separately, including unavailable bindings. */
public record ExecutionReport(String stage, ExecutionPolicy supplied, String requested, String actual,
                              String device, String fallbackReason, String numericalMode, String status) {}
