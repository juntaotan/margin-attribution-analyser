package dev.margintrace.margin_attribution_backend.algorithm.FGW;

/** Balanced FGW execution boundary. Sessions and submitted arrays belong to the calling run. */
@FunctionalInterface
public interface FgwComparator extends AutoCloseable {
    FgwEngine.Result compare(FgwEngine.Comparison input, FgwEngine.Config config);

    default void close() {}
}
