package dev.margintrace.margin_attribution_backend.algorithm.FGW;

import static org.junit.jupiter.api.Assertions.*;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import dev.margintrace.margin_attribution_backend.infrastructure.acceleration.NativeAcceleration;
import dev.margintrace.margin_attribution_backend.infrastructure.acceleration.NativeExecutionException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in actual-device JNI acceptance; configured runs fail rather than skip missing devices. */
@EnabledIfEnvironmentVariable(named = "MARGIN_TEST_GPU", matches = "true")
class NativeAccelerationTest {
    private static ExecutionPolicy policy(boolean cuda, boolean fallback) {
        return new ExecutionPolicy(true, cuda, null, fallback);
    }

    private static BackendRegistry.Selection select(String stage, boolean cuda, boolean fallback) {
        var selected = new BackendRegistry().resolve(stage, policy(cuda, fallback));
        assertEquals("AVAILABLE", selected.report().status(), selected.report().fallbackReason());
        assertEquals(cuda ? "CUDA" : "OPENCL", selected.report().actual());
        assertNotNull(selected.report().device());
        return selected;
    }

    private static void equivalent(FgwEngine.Result expected, FgwEngine.Result actual) {
        assertEquals(expected.rows, actual.rows);
        assertEquals(expected.columns, actual.columns);
        assertEquals(expected.left, actual.left);
        assertEquals(expected.right, actual.right);
        assertArrayEquals(expected.p, actual.p, 1e-8);
        assertArrayEquals(expected.q, actual.q, 1e-8);
        assertArrayEquals(expected.materialDistances, actual.materialDistances, 1e-8);
        assertArrayEquals(expected.leftDistances, actual.leftDistances, 1e-8);
        assertArrayEquals(expected.rightDistances, actual.rightDistances, 1e-8);
        assertEquals(expected.starts.size(), actual.starts.size());
        for (int i = 0; i < actual.starts.size(); ++i) {
            var a = actual.starts.get(i);
            var b = expected.starts.get(i);
            assertEquals(b.status, a.status);
            assertEquals(b.iterations, a.iterations);
            assertEquals(b.costs.material(), a.costs.material(), 1e-8);
            assertEquals(b.costs.structure(), a.costs.structure(), 1e-8);
            assertEquals(b.costs.fgw(), a.costs.fgw(), 1e-8);
            assertEquals(b.finalGap, a.finalGap, 1e-8);
            var marginal = FgwEngine.marginalErrors(a.correspondence, actual.rows, actual.columns);
            assertTrue(marginal.finite());
            assertTrue(marginal.minimum() >= -1e-9);
            assertEquals(0, marginal.row(), 1e-8);
            assertEquals(0, marginal.column(), 1e-8);
            assertEquals(0, marginal.total(), 1e-8);
            assertEquals(b.trace.size(), a.trace.size());
            for (int k = 0; k < a.trace.size(); ++k) {
                var x = a.trace.get(k);
                var y = b.trace.get(k);
                assertArrayEquals(y.correspondence, x.correspondence, 1e-8);
                assertArrayEquals(y.gradient, x.gradient, 1e-8);
                assertArrayEquals(y.direction, x.direction, 1e-8);
                assertEquals(y.gap, x.gap, 1e-8);
                assertEquals(y.step.a(), x.step.a(), 1e-8);
                assertEquals(y.step.b(), x.step.b(), 1e-8);
                assertEquals(y.step.gamma(), x.step.gamma(), 1e-8);
                assertEquals(x.ot.linearCost, x.ot.dualCost, 1e-8);
                assertEquals(y.marginals.row(), x.marginals.row(), 1e-8);
            }
        }
    }

    @Test
    void fixedFixtureTraceAndDeviceSimplexAllModes() throws Exception {
        var fixture = FgwFixtures.fixed();
        for (boolean cuda : List.of(true, false)) {
            for (int mode = 0; mode < 4; ++mode) {
                var config = new FgwEngine.Config();
                config.incrementalOtTree = (mode & 1) != 0;
                config.candidateOtPricing = (mode & 2) != 0;
                try (var engine = select("FGW", cuda, false)) {
                    var result = engine.fgw().compare(fixture, config);
                    equivalent(new FgwEngine().compare(fixture, config), result);
                    assertEquals(cuda ? "CUDA_DEVICE_EXACT_SIMPLEX" : "OPENCL_DEVICE_EXACT_SIMPLEX", result.execution);
                    config.captureTrace = true;
                    var traced = engine.fgw().compare(fixture, config);
                    equivalent(new FgwEngine().compare(fixture, config), traced);
                    assertTrue(traced.execution.startsWith("EXACT_CPU_OT_FALLBACK: captureTrace"));
                    assertTrue(engine.currentReport().numericalMode().contains("CPU_OT_FALLBACK"));
                    var evidence = Path.of("target/native-validation");
                    Files.createDirectories(evidence);
                    Files.writeString(
                            evidence.resolve((cuda ? "cuda" : "opencl") + "-jni-mode-" + mode + ".json"),
                            FgwFixtures.json(Map.of(
                                    "execution",
                                    cuda ? "CUDA_JNI" : "OPENCL_JNI",
                                    "fixed",
                                    FgwFixtures.result(traced),
                                    "deviceFixed",
                                    FgwFixtures.result(result),
                                    "device",
                                    engine.report().device(),
                                    "status",
                                    "PASS")));
                }
            }
        }
    }

    @Test
    void frontierRandomDagMicroUnitsAndMalformedBoundaries() {
        var random = new Random(20261012);
        for (boolean cuda : List.of(true, false))
            try (var selected = select("FRONTIER", cuda, false)) {
                for (int sample = 0; sample < 100; ++sample) {
                    int n = 2 + random.nextInt(30);
                    var successors = new ArrayList<Integer>();
                    int[] offsets = new int[n + 1], reverse = new int[n + 1];
                    int[] childCounts = new int[n];
                    for (int material = 0; material < n; ++material) {
                        for (int parent = material + 1; parent < n; ++parent)
                            if (random.nextInt(5) == 0) {
                                successors.add(parent);
                                childCounts[parent]++;
                            }
                        reverse[material + 1] = successors.size();
                    }
                    var terminals = new ArrayList<Integer>();
                    long[] values = new long[n];
                    byte[] comparable = new byte[n];
                    for (int i = 0; i < n; ++i) {
                        offsets[i + 1] = offsets[i] + childCounts[i];
                        if (childCounts[i] == 0) terminals.add(i);
                        values[i] = i == 0
                                ? Long.MIN_VALUE
                                : i == n - 1 ? Long.MAX_VALUE : random.nextLong(-1000000, 1000001);
                        comparable[i] = (byte) random.nextInt(2);
                    }
                    var graph = new BomUpwardGraph(
                            offsets,
                            reverse,
                            successors.stream().mapToInt(i -> i).toArray(),
                            terminals.stream().mapToInt(i -> i).toArray(),
                            values,
                            comparable);
                    for (long threshold : new long[] {0, 500000, Long.MAX_VALUE}) {
                        int[] expected = new BomUpwardEngine().run(graph, threshold),
                                actual = selected.frontier().run(graph, threshold);
                        Arrays.sort(expected);
                        Arrays.sort(actual);
                        assertArrayEquals(expected, actual, "sample=" + sample + ", threshold=" + threshold);
                    }
                }
                var edge = new BomUpwardGraph(
                        new int[] {0, 0, 1},
                        new int[] {0, 1, 1},
                        new int[] {1},
                        new int[] {0},
                        new long[] {0, Long.MIN_VALUE},
                        new byte[] {1, 1});
                assertArrayEquals(new int[] {1}, selected.frontier().run(edge, Long.MAX_VALUE));
                var invalid = new BomUpwardGraph(
                        edge.offsets(),
                        edge.reverseOffsets(),
                        new int[] {99},
                        edge.terminalNodes(),
                        edge.nodeValues(),
                        edge.nodeComparable());
                assertThrows(IllegalArgumentException.class, () -> selected.frontier()
                        .run(invalid, 0));
                assertArrayEquals(
                        new int[0],
                        selected.frontier()
                                .run(
                                        new BomUpwardGraph(
                                                new int[] {0},
                                                new int[] {0},
                                                new int[0],
                                                new int[0],
                                                new long[0],
                                                new byte[0]),
                                        0));
            }
    }

    @Test
    void sessionReuseChangedInputsMultiStartsAndClosure() {
        var fixture = FgwFixtures.fixed();
        var config = new FgwEngine.Config();
        for (boolean cuda : List.of(true, false)) {
            var selected = select("FGW", cuda, false);
            try (selected) {
                for (int i = 0; i < 12; ++i)
                    equivalent(
                            new FgwEngine().compare(fixture, config),
                            selected.fgw().compare(fixture, config));
                var matrices = List.of(
                        new double[] {1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6, 1. / 6},
                        new double[] {1. / 3, 1. / 6, 0, 0, 1. / 6, 1. / 3});
                var multiple = new FgwEngine.Comparison(fixture.left(), fixture.right(), matrices);
                equivalent(
                        new FgwEngine().compare(multiple, config),
                        selected.fgw().compare(multiple, config));
                double[] changed = fixture.left().embeddings();
                changed[0] = .2;
                var left = new FgwEngine.Graph(
                        fixture.left().productId(),
                        fixture.left().instanceId(),
                        0,
                        fixture.left().rowOffsets(),
                        fixture.left().colIndices(),
                        changed,
                        fixture.left().sources());
                var altered = new FgwEngine.Comparison(left, fixture.right());
                equivalent(
                        new FgwEngine().compare(altered, config), selected.fgw().compare(altered, config));
                assertThrows(IllegalArgumentException.class, () -> selected.fgw()
                        .compare(new FgwEngine.Comparison(left, fixture.right(), List.of(new double[6])), config));
                equivalent(
                        new FgwEngine().compare(altered, config), selected.fgw().compare(altered, config));
                config.maxIterations = 0;
                equivalent(
                        new FgwEngine().compare(altered, config), selected.fgw().compare(altered, config));
                config.maxIterations = 200;
                Thread.currentThread().interrupt();
                try {
                    assertThrows(java.util.concurrent.CancellationException.class, () -> selected.fgw()
                            .compare(altered, config));
                } finally {
                    Thread.interrupted();
                }
            }
            selected.close();
            assertThrows(IllegalStateException.class, () -> selected.fgw().compare(fixture, config));
        }
    }

    @Test
    void missingKernelFailsOrExplicitlyFallsBackWithoutFamilySwitch() {
        String old = System.getProperty("margintrace.opencl.kernelDir");
        try (var unavailable = select("FGW", false, false);
                var fallback = select("FGW", false, true);
                var frontier = select("FRONTIER", false, true);
                var strictFrontier = select("FRONTIER", false, false)) {
            System.setProperty("margintrace.opencl.kernelDir", "/nonexistent-margintrace-test-kernels");
            var fixture = FgwFixtures.fixed();
            var config = new FgwEngine.Config();
            assertThrows(NativeExecutionException.class, () -> unavailable.fgw().compare(fixture, config));
            assertEquals("UNAVAILABLE", unavailable.currentReport().status());
            var result = fallback.fgw().compare(fixture, config);
            equivalent(new FgwEngine().compare(fixture, config), result);
            assertEquals("JAVA_SERIAL_EXACT_SIMPLEX", result.execution);
            assertEquals("OPENCL", fallback.currentReport().requested());
            assertEquals("JAVA_CPU", fallback.currentReport().actual());
            assertNotNull(fallback.currentReport().fallbackReason());
            var g = new BomUpwardGraph(
                    new int[] {0, 0, 1},
                    new int[] {0, 1, 1},
                    new int[] {1},
                    new int[] {0},
                    new long[] {0, 9007199254740993L},
                    new byte[] {1, 1});
            assertThrows(
                    NativeExecutionException.class,
                    () -> strictFrontier.frontier().run(g, 0));
            assertEquals("UNAVAILABLE", strictFrontier.currentReport().status());
            config.maxIterations = 0;
            equivalent(new FgwEngine().compare(fixture, config), fallback.fgw().compare(fixture, config));
            assertArrayEquals(new int[] {1}, frontier.frontier().run(g, 9007199254740993L));
            assertEquals("JAVA_CPU", frontier.currentReport().actual());
            var missing = new BackendRegistry().resolve("FGW", policy(false, false));
            assertEquals("UNAVAILABLE", missing.report().status());
        } finally {
            if (old == null) System.clearProperty("margintrace.opencl.kernelDir");
            else System.setProperty("margintrace.opencl.kernelDir", old);
        }
    }

    @Test
    void capabilityEndpointListsDevicesAndStages() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisRunController(null))
                .build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/analysis/capabilities"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.cpu.actual")
                        .value("JAVA_CPU"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.devices.cuda[0].id")
                        .isString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.devices.opencl[0].fgw")
                        .value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.stages.cuda.frontier.status")
                        .value("AVAILABLE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.stages.opencl.fgw.status")
                        .value("AVAILABLE"));
    }

    @Test
    void deviceInventoryAndInvalidSelection() {
        for (boolean cuda : List.of(true, false)) {
            var inventory = NativeAcceleration.inventory(cuda ? "CUDA" : "OPENCL");
            assertFalse(inventory.devices().isEmpty(), inventory.unavailableReason());
            assertTrue(inventory.devices().stream().anyMatch(NativeAcceleration.Device::fgw));
            var unavailable =
                    new BackendRegistry().resolve("FGW", new ExecutionPolicy(true, cuda, "2147483647", false));
            assertNull(unavailable.fgw());
            assertEquals("UNAVAILABLE", unavailable.report().status());
            try (var fallback =
                    new BackendRegistry().resolve("FGW", new ExecutionPolicy(true, cuda, "2147483647", true))) {
                assertEquals("JAVA_CPU", fallback.report().actual());
            }
        }
    }
}
