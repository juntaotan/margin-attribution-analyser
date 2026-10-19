package dev.margintrace.margin_attribution_backend.importation.model;

/**
 * Status of an import job. A job moves through validation, raw storage, transformation and writing; each stage can
 * fail, and a job can be cancelled before it completes. A failed write may be retried.
 */
public enum ImportStatus {
    PENDING,
    VALIDATING,
    VALIDATED,
    VALIDATING_FAILED,
    STORING,
    STORED,
    STORING_FAILED,
    TRANSFORMING,
    TRANSFORMED,
    TRANSFORMING_FAILED,
    WRITING_TO_DATALAKE,
    WRITE_SUCCESS,
    WRITE_FAILED,
    CANCELLED;

    /**
     * Determines whether this status can transition to the specified next status.
     *
     * @param next the target status of the transition
     * @return {@code true} if the transition is allowed; otherwise {@code false}
     */
    public boolean canTransitionTo(ImportStatus next) {
        return switch (this) {
            case PENDING -> next == VALIDATING || next == CANCELLED;
            case VALIDATING -> next == VALIDATED || next == VALIDATING_FAILED || next == CANCELLED;
            case VALIDATED -> next == STORING || next == CANCELLED;
            case STORING -> next == STORED || next == STORING_FAILED || next == CANCELLED;
            case STORED -> next == TRANSFORMING || next == CANCELLED;
            case TRANSFORMING -> next == TRANSFORMED || next == TRANSFORMING_FAILED || next == CANCELLED;
            case TRANSFORMED, WRITE_FAILED -> next == WRITING_TO_DATALAKE || next == CANCELLED;
            case WRITING_TO_DATALAKE -> next == WRITE_SUCCESS || next == WRITE_FAILED || next == CANCELLED;
            case VALIDATING_FAILED, STORING_FAILED, TRANSFORMING_FAILED, CANCELLED, WRITE_SUCCESS -> false;
        };
    }

    /**
     * @return whether the job has finished, successfully or not; {@code WRITE_FAILED} counts as finished although
     *     it may still be retried
     */
    public boolean isTerminal() {
        return this == VALIDATING_FAILED
                || this == TRANSFORMING_FAILED
                || this == WRITE_SUCCESS
                || this == WRITE_FAILED
                || this == CANCELLED
                || this == STORING_FAILED;
    }
}
