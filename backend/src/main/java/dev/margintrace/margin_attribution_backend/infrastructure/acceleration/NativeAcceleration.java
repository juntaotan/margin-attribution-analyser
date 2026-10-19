package dev.margintrace.margin_attribution_backend.infrastructure.acceleration;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import java.nio.file.*;
import java.util.*;

/**
 * CUDA/OpenCL adapters for exact frontier and FP64 FGW. Each comparator belongs to one analysis
 * run, owns one fixed-baseline native session and closes it before switching baselines or exiting.
 * Only explicit runtime failures permit caller-authorised CPU fallback; invalid/numerical inputs
 * are never hidden by fallback. Device IDs are CUDA indices or OpenCL platform:device pairs.
 */
public final class NativeAcceleration {
    public record Device(String id, String name, boolean frontier, boolean fgw) {}

    public record Inventory(List<Device> devices, String unavailableReason) {}

    private static final Map<String, String> loadFailures = new java.util.concurrent.ConcurrentHashMap<>();

    private NativeAcceleration() {}

    /** Enumerates GPU devices without allocating calculation sessions or selecting another family. */
    public static Inventory inventory(String family) {
        if (loadFailures.containsKey(family)) return new Inventory(List.of(), loadFailures.get(family));
        try {
            String[][] rows = family.equals("CUDA") ? NativeBindings.Cuda.devices() : NativeBindings.OpenCl.devices();
            boolean frontierKernel =
                    !family.equals("OPENCL") || Files.isReadable(Path.of(NativeBindings.kernels(), "bom_upward.cl"));
            boolean fgwKernel =
                    !family.equals("OPENCL") || Files.isReadable(Path.of(NativeBindings.kernels(), "fgw.cl"));
            return new Inventory(
                    Arrays.stream(rows)
                            .map(r -> new Device(r[0], r[1], frontierKernel, fgwKernel && Boolean.parseBoolean(r[2])))
                            .toList(),
                    rows.length == 0 ? family + "_GPU_DEVICE_UNAVAILABLE" : null);
        } catch (LinkageError error) {
            String reason = family + "_INITIALISATION_UNAVAILABLE: " + error.getMessage();
            loadFailures.putIfAbsent(family, reason);
            return new Inventory(List.of(), reason);
        } catch (NativeExecutionException error) {
            return new Inventory(List.of(), family + "_INITIALISATION_UNAVAILABLE: " + error.getMessage());
        }
    }

    /** Resolves a stage against the requested runtime inventory; GPU allocation remains lazy. */
    public static BackendRegistry.Selection resolve(String stage, ExecutionPolicy policy) {
        Inventory inventory = inventory(policy.family());
        if (inventory.unavailableReason() != null) throw new NativeExecutionException(inventory.unavailableReason());
        if (policy.family().equals("OPENCL")) {
            Path kernel = Path.of(NativeBindings.kernels(), stage.equals("FRONTIER") ? "bom_upward.cl" : "fgw.cl");
            if (!Files.isReadable(kernel)) throw new NativeExecutionException("OPENCL_KERNEL_UNAVAILABLE: " + kernel);
        }
        Device device = inventory.devices().stream()
                .filter(d -> policy.deviceId() == null
                        || d.id().equals(policy.deviceId())
                        || policy.family().equals("OPENCL") && d.id().equals("0:" + policy.deviceId()))
                .filter(d -> stage.equals("FRONTIER") ? d.frontier() : d.fgw())
                .findFirst()
                .orElseThrow(() -> new NativeExecutionException(
                        policy.family() + "_DEVICE_OR_STAGE_UNAVAILABLE: " + policy.deviceId()));
        ExecutionReport report = new ExecutionReport(
                stage,
                policy,
                policy.family(),
                policy.family(),
                device.id(),
                null,
                stage.equals("FRONTIER") ? "SIGNED_LONG_MICRO_UNITS" : "FP64/EXACT_SIMPLEX",
                "AVAILABLE");
        return stage.equals("FRONTIER")
                ? new BackendRegistry.Selection(new Frontier(policy, device.id(), report), null, report)
                : new BackendRegistry.Selection(null, new Comparator(policy, device.id(), report), report);
    }

    private static ExecutionReport fallback(ExecutionReport report, RuntimeException error) {
        return new ExecutionReport(
                report.stage(),
                report.supplied(),
                report.requested(),
                "JAVA_CPU",
                null,
                error.getMessage(),
                report.numericalMode(),
                "AVAILABLE");
    }

    private static final class Frontier implements FrontierEngine, ReportedExecution {
        private final ExecutionPolicy policy;
        private final String device;
        private ExecutionReport report;

        Frontier(ExecutionPolicy policy, String device, ExecutionReport report) {
            this.policy = policy;
            this.device = device;
            this.report = report;
        }

        public int[] run(BomUpwardGraph graph, long threshold) {
            try {
                return policy.isNvidia()
                        ? NativeBindings.Cuda.frontier(
                                device,
                                NativeBindings.kernels(),
                                graph.offsets(),
                                graph.reverseOffsets(),
                                graph.reverseSuccessors(),
                                graph.terminalNodes(),
                                graph.nodeValues(),
                                graph.nodeComparable(),
                                threshold)
                        : NativeBindings.OpenCl.frontier(
                                device,
                                NativeBindings.kernels(),
                                graph.offsets(),
                                graph.reverseOffsets(),
                                graph.reverseSuccessors(),
                                graph.terminalNodes(),
                                graph.nodeValues(),
                                graph.nodeComparable(),
                                threshold);
            } catch (NativeExecutionException error) {
                if (!policy.allowCpuFallback()) {
                    report = new ExecutionReport(
                            report.stage(),
                            policy,
                            policy.family(),
                            null,
                            device,
                            error.getMessage(),
                            report.numericalMode(),
                            "UNAVAILABLE");
                    throw error;
                }
                report = fallback(report, error);
                return new BomUpwardEngine().run(graph, threshold);
            }
        }

        public ExecutionReport executionReport() {
            return report;
        }
    }

    private static final class Comparator implements FgwComparator, ReportedExecution {
        private final ExecutionPolicy policy;
        private final String device;
        private ExecutionReport report;
        private FgwEngine.Graph baseline;
        private double[] configuration;
        private final Set<String> placements = new LinkedHashSet<>();
        private long session;
        private JavaFgwComparator cpu;
        private boolean closed;

        Comparator(ExecutionPolicy policy, String device, ExecutionReport report) {
            this.policy = policy;
            this.device = device;
            this.report = report;
        }

        public synchronized FgwEngine.Result compare(FgwEngine.Comparison input, FgwEngine.Config config) {
            if (closed) throw new IllegalStateException("FGW comparator is closed");
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("Analysis interrupted");
            // Validate before any device call, preserving the CPU/native numerical input contract.
            FgwEngine.validateConfig(config);
            if (cpu != null) {
                if (!sameBaseline(baseline, input.left()) || !Arrays.equals(configuration, settings(config))) {
                    cpu.close();
                    cpu = new JavaFgwComparator();
                    baseline = snapshot(input.left());
                    configuration = settings(config);
                }
                return cpu.compare(input, config);
            }
            try {
                if (!sameBaseline(baseline, input.left()) || !Arrays.equals(configuration, settings(config))) {
                    release();
                    var ownedBaseline = snapshot(input.left());
                    session = policy.isNvidia()
                            ? NativeBindings.Cuda.create(device, NativeBindings.kernels(), ownedBaseline, config)
                            : NativeBindings.OpenCl.create(device, NativeBindings.kernels(), ownedBaseline, config);
                    baseline = ownedBaseline;
                    configuration = settings(config);
                }
                double[][] starts = input.initialMatrices().toArray(double[][]::new);
                FgwEngine.Result result = policy.isNvidia()
                        ? NativeBindings.Cuda.compare(session, input.right(), starts)
                        : NativeBindings.OpenCl.compare(session, input.right(), starts);
                result.left = new FgwEngine.SourceMapping(
                        input.left().productId(),
                        input.left().instanceId(),
                        input.left().rootIndex(),
                        input.left().sources());
                result.right = new FgwEngine.SourceMapping(
                        input.right().productId(),
                        input.right().instanceId(),
                        input.right().rootIndex(),
                        input.right().sources());
                placements.add(result.execution);
                report = new ExecutionReport(
                        report.stage(),
                        policy,
                        policy.family(),
                        policy.family(),
                        device,
                        null,
                        "FP64/" + String.join("; ", placements),
                        "AVAILABLE");
                return result;
            } catch (NativeExecutionException error) {
                release();
                if (!policy.allowCpuFallback()) {
                    report = new ExecutionReport(
                            report.stage(),
                            policy,
                            policy.family(),
                            null,
                            device,
                            error.getMessage(),
                            report.numericalMode(),
                            "UNAVAILABLE");
                    throw error;
                }
                report = fallback(report, error);
                if (!placements.isEmpty())
                    report = new ExecutionReport(
                            report.stage(),
                            policy,
                            policy.family(),
                            policy.family() + "+JAVA_CPU",
                            null,
                            report.fallbackReason(),
                            report.numericalMode() + "; JAVA_SERIAL_EXACT_SIMPLEX",
                            "AVAILABLE");
                cpu = new JavaFgwComparator();
                baseline = snapshot(input.left());
                configuration = settings(config);
                return cpu.compare(input, config);
            }
        }

        private static FgwEngine.Graph snapshot(FgwEngine.Graph g) {
            return new FgwEngine.Graph(
                    g.productId(),
                    g.instanceId(),
                    g.rootIndex(),
                    g.rowOffsets(),
                    g.colIndices(),
                    g.embeddings(),
                    g.sources());
        }

        private static boolean sameBaseline(FgwEngine.Graph a, FgwEngine.Graph b) {
            return a != null
                    && a.productId().equals(b.productId())
                    && a.instanceId().equals(b.instanceId())
                    && a.rootIndex() == b.rootIndex()
                    && a.sources().equals(b.sources())
                    && Arrays.equals(a.rowOffsets(), b.rowOffsets())
                    && Arrays.equals(a.colIndices(), b.colIndices())
                    && Arrays.equals(a.embeddings(), b.embeddings());
        }

        private static double[] settings(FgwEngine.Config c) {
            return new double[] {
                c.alpha,
                c.tolAbs,
                c.tolRel,
                c.maxIterations,
                c.tolGapAbs,
                c.tolGapRel,
                c.costScaleFloor,
                c.feasibilityTolerance,
                c.numericalTolerance,
                c.otTolerance,
                c.captureTrace ? 1 : 0,
                c.incrementalOtTree ? 1 : 0,
                c.candidateOtPricing ? 1 : 0
            };
        }

        private void release() {
            long handle = session;
            session = 0;
            baseline = null;
            configuration = null;
            if (handle == 0) return;
            if (policy.isNvidia()) NativeBindings.Cuda.close(handle);
            else NativeBindings.OpenCl.close(handle);
        }

        public synchronized void close() {
            if (!closed) {
                closed = true;
                release();
                if (cpu != null) cpu.close();
            }
        }

        public ExecutionReport executionReport() {
            return report;
        }
    }
}
