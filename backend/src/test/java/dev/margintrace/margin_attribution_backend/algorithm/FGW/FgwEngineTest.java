package dev.margintrace.margin_attribution_backend.algorithm.FGW;

import static dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FgwEngineTest {
    static void matrix(double[] expected, double[] actual) {
        assertArrayEquals(expected, actual, 1e-8);
    }

    @Test
    void prescribedIntermediateValuesAndSources() {
        Config c = new Config();
        c.captureTrace = true;
        Result r = new FgwEngine().compare(FgwFixtures.fixed(), c);
        StartResult s = r.starts.get(0);
        matrix(new double[] {0, 1, 4, 4, 1, 0}, r.materialDistances);
        matrix(new double[] {0, 1, 1, 0}, r.leftDistances);
        matrix(new double[] {0, 1, 2, 1, 0, 1, 2, 1, 0}, r.rightDistances);
        matrix(new double[] {.5, .5}, r.p);
        matrix(new double[] {1. / 3, 1. / 3, 1. / 3}, r.q);
        assertEquals(2, s.trace.size());
        Iteration a = s.trace.get(0), b = s.trace.get(1);
        matrix(new double[] {1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6}, a.correspondence);
        matrix(new double[] {1.8666666667, 1, 2.6666666667, 2.6666666667, 1, 1.8666666667}, a.gradient);
        double[] expected = {1. / 3, 1. / 6, 0, 0, 1. / 6, 1. / 3};
        matrix(expected, a.ot.correspondence);
        matrix(new double[] {1. / 6, 0, -1. / 6, -1. / 6, 0, 1. / 6}, a.direction);
        assertEquals(1.6666666667, a.costs.material(), 1e-8);
        assertEquals(.9444444444, a.costs.structure(), 1e-8);
        assertEquals(1.0888888889, a.costs.fgw(), 1e-8);
        assertEquals(.2666666667, a.gap, 1e-8);
        assertEquals(1, a.step.gamma());
        matrix(expected, b.correspondence);
        matrix(expected, b.ot.correspondence);
        matrix(new double[] {.8, 1, 3.7333333333, 3.7333333333, 1, .8}, b.gradient);
        assertEquals(.3333333333, b.costs.material(), 1e-8);
        assertEquals(.5, b.costs.structure(), 1e-8);
        assertEquals(.4666666667, b.costs.fgw(), 1e-8);
        assertEquals(0, b.gap, 1e-8);
        assertTrue(s.converged());
        assertEquals(1, s.iterations);
        for (Iteration t : s.trace) {
            assertTrue(t.marginals.finite());
            assertEquals(0, t.marginals.row(), 1e-8);
            assertEquals(0, t.marginals.column(), 1e-8);
            assertEquals(0, t.marginals.total(), 1e-8);
            assertEquals(t.ot.linearCost, t.ot.dualCost, 1e-8);
            assertTrue(t.ot.fullPricingScans > 0);
        }
        assertEquals("9007199254740993.01", r.left.nodes().get(0).amount());
        assertEquals("1.000000000000000001", r.right.nodes().get(1).quantity());
        assertTrue(r.right.nodes().get(1).fifoAllocationIds().isEmpty());
    }

    @Test
    void modesWarmStartsAndStopping() {
        for (int mode = 0; mode < 4; ++mode) {
            Config c = new Config();
            c.incrementalOtTree = (mode & 1) != 0;
            c.candidateOtPricing = (mode & 2) != 0;
            Result r = new FgwEngine().compare(FgwFixtures.fixed(), c);
            assertEquals(.4666666667, r.starts.get(0).costs.fgw(), 1e-8);
            var f = FgwFixtures.fixed();
            Session session = new Session(f.left(), c);
            Result multi = session.compare(f.right(), List.of(new double[] {1. / 3, 1. / 6, 0, 0, 1. / 6, 1. / 3}));
            assertEquals(1, multi.starts.size());
            assertEquals(0, multi.starts.get(0).iterations);
            assertEquals(
                    .4666666667, session.compare(f.right()).starts.get(0).costs.fgw(), 1e-8);
        }
        Config zero = new Config();
        zero.maxIterations = 0;
        assertEquals(
                Status.MAX_ITERATIONS,
                new FgwEngine().compare(FgwFixtures.fixed(), zero).starts.get(0).status);
        zero.maxIterations = 1;
        assertTrue(
                new FgwEngine().compare(FgwFixtures.fixed(), zero).starts.get(0).converged());
        Config c = new Config();
        Config stalled = new Config();
        stalled.tolAbs = 100;
        stalled.tolGapAbs = 0;
        stalled.tolGapRel = 0;
        MarginalErrors feasible = new MarginalErrors(0, 0, 0, 0, true);
        assertEquals(Status.COST_STALLED, stoppingStatus(.1, new Costs(1, 1, 1), feasible, 1, 1.01, true, stalled));
        assertEquals(Status.NUMERICAL_FAILURE, stoppingStatus(-.1, new Costs(1, 1, 1), feasible, 1, 1, false, c));
        assertEquals(.25, quadraticLineSearch(-1, -.8, c).gamma(), 1e-8);
        assertEquals(1, quadraticLineSearch(0, -1, c).gamma());
        assertThrows(IllegalArgumentException.class, () -> new FgwEngine()
                .compare(
                        new Comparison(
                                FgwFixtures.fixed().left(), FgwFixtures.fixed().right(), List.of(new double[6])),
                        c));
        assertThrows(
                ArithmeticException.class, () -> exactLinearOt(new double[] {Double.NaN}, 1, 1, c, new OtWorkspace()));
    }

    @Test
    void orderingUnequalSizesInvalidInputsAndFailureRecovery() {
        var report = FgwFixtures.report();
        assertNotNull(report.get("ordering"));
        Graph left = FgwFixtures.graph(new int[] {0, 2, 2, 2}, new int[] {1, 2}, new double[] {0, 1, 2}, "l"),
                right = FgwFixtures.graph(new int[] {0, 2, 2, 2}, new int[] {1, 2}, new double[] {0, 2, 1}, "r");
        Result ordered = new FgwEngine()
                .compare(new Comparison(left, right, List.of(new double[] {1. / 3, 0, 0, 0, 0, 1. / 3, 0, 1. / 3, 0})));
        assertEquals(0, ordered.starts.get(ordered.bestStart).costs.fgw(), 1e-8);
        var f = FgwFixtures.fixed();
        Session session = new Session(f.left(), new Config());
        Graph disconnected = FgwFixtures.graph(new int[] {0, 0, 0}, new int[] {}, new double[] {0, 2}, "bad");
        assertThrows(IllegalArgumentException.class, () -> session.compare(disconnected));
        Graph wrong = new Graph(
                "another",
                f.right().instanceId(),
                0,
                f.right().rowOffsets(),
                f.right().colIndices(),
                f.right().embeddings(),
                f.right().sources());
        assertThrows(IllegalArgumentException.class, () -> session.compare(wrong));
        Graph large =
                FgwFixtures.graph(new int[] {0, 1, 2, 3, 3}, new int[] {1, 2, 3}, new double[] {0, 1, 2, 3}, "larger");
        assertEquals(4, session.compare(large).columns);
        double[] overflow = f.right().embeddings().clone();
        overflow[0] = 1e308;
        Graph bad = new Graph(
                f.right().productId(),
                "overflow",
                0,
                f.right().rowOffsets(),
                f.right().colIndices(),
                overflow,
                f.right().sources());
        assertEquals(Status.NUMERICAL_FAILURE, session.compare(bad).starts.get(0).status);
        assertTrue(session.compare(f.right()).starts.get(0).converged());
    }
}
