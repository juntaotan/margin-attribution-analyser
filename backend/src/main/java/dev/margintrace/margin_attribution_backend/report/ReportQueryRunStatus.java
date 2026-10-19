package dev.margintrace.margin_attribution_backend.report;

/**
 * Lifecycle of a query run: running, then succeeded or failed; a succeeded run becomes applied once written into
 * the document.
 */
public enum ReportQueryRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    APPLIED
}
