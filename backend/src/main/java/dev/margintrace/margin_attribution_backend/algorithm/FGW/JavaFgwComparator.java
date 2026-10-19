package dev.margintrace.margin_attribution_backend.algorithm.FGW;

/**
 * One calling-run comparator reuses the current left-tree preparation across its remaining pairs.
 * Inputs/configuration are immutable within the run. Each target and start is solved afresh; no
 * correspondence or objective is cached. Java sessions remain confined to the creating thread.
 */
public final class JavaFgwComparator implements FgwComparator {
    private String baseline;
    private FgwEngine.Session session;

    /**
     * Compares a pair, reusing the prepared baseline while consecutive comparisons share the same left instance.
     */
    public FgwEngine.Result compare(FgwEngine.Comparison input, FgwEngine.Config config) {
        if (!input.left().instanceId().equals(baseline)) {
            session = new FgwEngine.Session(input.left(), config);
            baseline = input.left().instanceId();
        }
        return session.compare(input.right(), input.initialMatrices());
    }

    /**
     * Releases the prepared baseline.
     */
    public void close() {
        session = null;
        baseline = null;
    }
}
