package dev.margintrace.margin_attribution_backend.algorithm.FGW;

import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import java.nio.file.*;
import java.util.*;
import static dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.*;

/** Standalone JNI numerical evidence driver, also usable under JVM/native memory diagnostics. */
public final class NativeFgwFixtures {
    private NativeFgwFixtures() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: NativeFgwFixtures CUDA|OPENCL output.json");
        boolean cuda = args[0].equals("CUDA");
        if (!cuda && !args[0].equals("OPENCL")) throw new IllegalArgumentException("Unknown engine family");
        var config = new Config(); config.captureTrace = true;
        int mode = Integer.parseInt(System.getenv().getOrDefault("FGW_SIMPLEX_MODE", "3"));
        config.incrementalOtTree = (mode & 1) != 0; config.candidateOtPricing = (mode & 2) != 0;
        try (var selected = new BackendRegistry().resolve("FGW", new ExecutionPolicy(true,cuda,null,false))) {
            if (selected.fgw() == null) throw new IllegalStateException(selected.report().fallbackReason());
            var f = FgwFixtures.fixed(); var comparator = selected.fgw();
            var fixed = comparator.compare(f,config);
            var multiple = comparator.compare(new Comparison(f.left(),f.right(),List.of(
                    new double[]{1./6,1./6,1./6,1./6,1./6,1./6},new double[]{1./3,1./6,0,0,1./6,1./3})),config);
            var left = FgwFixtures.graph(new int[]{0,2,2,2},new int[]{1,2},new double[]{0,1,2},"siblings-left");
            var right = FgwFixtures.graph(new int[]{0,2,2,2},new int[]{1,2},new double[]{0,2,1},"siblings-right");
            var starts = List.of(new double[]{1./9,1./9,1./9,1./9,1./9,1./9,1./9,1./9,1./9},new double[]{1./3,0,0,0,0,1./3,0,1./3,0});
            var ordering = comparator.compare(new Comparison(left,right,starts),config);
            var reversed = comparator.compare(new Comparison(right,left,starts),config);
            config.captureTrace = false;
            var device = comparator.compare(f,config);
            var result = FgwFixtures.map("execution", args[0] + "_JNI", "fixed", FgwFixtures.result(fixed),
                    "unequalMultiStart", FgwFixtures.result(multiple), "ordering", FgwFixtures.result(ordering),
                    "orderingReverse", FgwFixtures.result(reversed), "deviceFixed", FgwFixtures.result(device),
                    "otExecution",device.execution,"device",selected.report().device());
            Files.writeString(Path.of(args[1]),FgwFixtures.json(result) + "\n");
        }
    }
}
