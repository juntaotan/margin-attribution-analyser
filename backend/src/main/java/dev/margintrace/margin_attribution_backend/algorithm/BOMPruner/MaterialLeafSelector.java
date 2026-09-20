package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

/** Selects material-side leaves (no incoming edges) with sufficient cost difference. */
final class MaterialLeafSelector extends PruningHandler {
    MaterialLeafSelector(PruningHandler next) {
        super(next);
    }

    @Override
    protected void process(PruningContext context) {
        boolean[] hasIncoming = new boolean[context.actualGraph.nodes().length];
        for (int successor : context.actualGraph.successors()) {
            hasIncoming[successor] = true;
        }
        for (int position = 0; position < hasIncoming.length; position++) {
            if (!hasIncoming[position]) {
                var difference = context.costDifference(position);
                if (difference != null && difference.compareTo(context.leafThreshold) >= 0) {
                    context.materialLeafPositions.add(position);
                }
            }
        }
    }
}
