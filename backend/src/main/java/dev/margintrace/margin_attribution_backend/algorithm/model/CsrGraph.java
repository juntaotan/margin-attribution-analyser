package dev.margintrace.margin_attribution_backend.algorithm.model;

/**
 * Directed graph in compressed sparse row form. The successors of node {@code i} are {@code successors[offset[i]]}
 * to {@code successors[offset[i + 1] - 1]}; edges run from a material to the item that receives it.
 */
public record CsrGraph(Node[] nodes, int[] offset, int[] successors) {}
