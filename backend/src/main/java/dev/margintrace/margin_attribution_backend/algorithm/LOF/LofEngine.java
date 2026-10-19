package dev.margintrace.margin_attribution_backend.algorithm.LOF;

/** Calculates one LOF score per four-feature order. */
public final class LofEngine {

    /** Calculate Euclidean distance between two orders. */
    private static float distance(float[] left, float[] right) {
        float squaredDistance = 0.0f;

        // Compare corresponding features of the two orders.
        for (int feature = 0; feature < 4; feature++) {
            float difference = left[feature] - right[feature];
            squaredDistance += difference * difference;
        }
        return (float) Math.sqrt(squaredDistance);
    }

    /** Return LOF scores in input order, including all neighbors tied at the kth distance. */
    public float[] run(float[][] orders, int k) {
        int count = orders.length;
        if (k <= 0 || k >= count) {
            throw new IllegalArgumentException("LOF requires 1 <= k < number of orders");
        }

        // Reuse the distance matrix in all subsequent LOF stages.
        float[][] distances = new float[count][count];

        // Give each order one row of distances to every other order.
        for (int target = 0; target < count; target++) {
            for (int candidate = 0; candidate < count; candidate++) {
                distances[target][candidate] =
                        candidate == target ? Float.POSITIVE_INFINITY : distance(orders[target], orders[candidate]);
            }
        }

        // The kth distance sets the inclusive neighbor boundary for each order.
        float[] kDistances = new float[count];

        // Select the kth (distance, index) pair from each row.
        for (int target = 0; target < count; target++) {
            float previousDistance = -1.0f;
            int previousIndex = -1;

            // Select the next nearest neighbor k times.
            for (int rank = 0; rank < k; rank++) {
                float bestDistance = Float.POSITIVE_INFINITY;
                int bestIndex = -1;

                // Find the nearest candidate after the last selected pair.
                for (int candidate = 0; candidate < count; candidate++) {
                    if (candidate == target) continue;
                    float candidateDistance = distances[target][candidate];
                    if (candidateDistance < previousDistance
                            || (candidateDistance == previousDistance && candidate <= previousIndex)) continue;
                    if (candidateDistance < bestDistance
                            || (candidateDistance == bestDistance && (bestIndex < 0 || candidate < bestIndex))) {
                        bestDistance = candidateDistance;
                        bestIndex = candidate;
                    }
                }
                previousDistance = bestDistance;
                previousIndex = bestIndex;
            }
            kDistances[target] = previousDistance;
        }

        // Store each order's local reachability density before calculating LOF.
        double[] lrd = new double[count];

        // Compute the average reach distance of each order's neighbors.
        for (int target = 0; target < count; target++) {
            double reachDistanceSum = 0.0;
            int neighborCount = 0;

            // Include every neighbor at or inside the kth distance, including ties.
            for (int neighbor = 0; neighbor < count; neighbor++) {
                float actualDistance = distances[target][neighbor];
                if (neighbor == target || actualDistance > kDistances[target]) continue;
                reachDistanceSum += Math.max(actualDistance, kDistances[neighbor]);
                neighborCount++;
            }
            lrd[target] = reachDistanceSum == 0.0 ? Double.POSITIVE_INFINITY : neighborCount / reachDistanceSum;
        }

        // Scores preserve the input order for the caller.
        float[] scores = new float[count];

        // Average each neighbor's LRD relative to the target's LRD.
        for (int target = 0; target < count; target++) {
            double ratioSum = 0.0;
            int neighborCount = 0;

            // Use the same tie-inclusive neighbor set as the LRD stage.
            for (int neighbor = 0; neighbor < count; neighbor++) {
                if (neighbor == target || distances[target][neighbor] > kDistances[target]) continue;
                double ratio = Double.isInfinite(lrd[target]) && Double.isInfinite(lrd[neighbor])
                        ? 1.0
                        : lrd[neighbor] / lrd[target];
                ratioSum += ratio;
                neighborCount++;
            }
            scores[target] = (float) (ratioSum / neighborCount);
        }
        return scores;
    }
}
