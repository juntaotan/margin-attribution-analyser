package dev.margintrace.margin_attribution_backend.algorithm.model;

/** A directed CSR edge, including its index so parallel edges remain distinct. */
public record GraphEdge(int fromPosition, int toPosition, int edgeIndex) {
}
