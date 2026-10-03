package dev.margintrace.margin_attribution_backend.algorithm.LOF;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the seven-point formula and one global 14,000-order LOF call. */
public class LofEngineTests {
    private final LofEngine engine = new LofEngine();

    /** Verify known scores, then time one global dataset with one anomaly. */
    @Test
    public void calculatesOneBatchOfOrders() {
        int k = 2;
        int orderCount = 14_000;
        int normalCount = orderCount - 1;

        // A-G provide the fixed seven-point reference case.
        float[][] baseOrders = {
                {7.0f, 2.0f, 1.0f, 1.0f},
                {7.1f, 1.8f, 1.1f, 0.9f},
                {6.9f, 2.2f, 0.9f, 1.1f},
                {0.1f, 2.0f, 1.0f, 1.0f},
                {0.2f, 1.8f, 1.1f, 0.9f},
                {0.0f, 2.2f, 0.9f, 1.1f},
                {0.5f, 0.5f, 0.5f, 0.5f}
        };
        float[] expected = {1.333333333f, 0.875f, 0.875f, 1.333333333f,
                0.875f, 0.875f, 3.552414237f};

        // Verify the formula before enlarging the shared neighbor population.
        float[] referenceScores = engine.run(baseOrders, k);
        for (int point = 0; point < baseOrders.length; point++) {
            assertEquals(expected[point], referenceScores[point], 1e-3f, "Reference point " + point);
        }
        System.out.println("Java LOF seven-point reference: PASS");

        // Expand A-F into nearby distinct points; include G only once.
        float[][] orders = new float[orderCount][4];
        for (int index = 0; index < normalCount; index++) {
            int prototype = index % 6;
            int copy = index / 6;
            System.arraycopy(baseOrders[prototype], 0, orders[index], 0, 4);
            orders[index][0] += copy * 1e-5f;
        }
        System.arraycopy(baseOrders[6], 0, orders[normalCount], 0, 4);

        // Warm the same global dataset before timing one complete engine call.
        System.out.printf("Java LOF: warming up %d global points...%n", orderCount);
        engine.run(orders, k);

        // Measure one complete engine call for all 14,000 points.
        System.out.printf("Java LOF: processing %d global points in one call...%n", orderCount);
        System.out.flush();
        long start = System.nanoTime();
        float[] scores = engine.run(orders, k);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        assertEquals(orders.length, scores.length);

        // Check all scores and compare the sole anomaly with every normal point.
        float maxNormal = 0.0f;
        for (int index = 0; index < normalCount; index++) {
            assertTrue(Float.isFinite(scores[index]) && scores[index] > 0.0f, "Normal point " + index);
            maxNormal = Math.max(maxNormal, scores[index]);
        }
        float anomaly = scores[normalCount];
        assertTrue(Float.isFinite(anomaly) && anomaly > maxNormal, "G must have the highest LOF");

        // Print representative A-F scores and the single G score.
        for (int point = 0; point < 6; point++) {
            System.out.printf(Locale.ROOT, "%c  LOF=%.6f%n", 'A' + point, scores[point]);
        }
        System.out.printf(Locale.ROOT, "G  LOF=%.6f  max normal=%.6f  PASS%n", anomaly, maxNormal);

        System.out.printf(Locale.ROOT,
                "Java LOF global batch: %d points, 1 call, %.6f s, %.3f ms, %.2f points/s%n",
                orderCount, seconds, seconds * 1000.0, orderCount / seconds);
    }
}
