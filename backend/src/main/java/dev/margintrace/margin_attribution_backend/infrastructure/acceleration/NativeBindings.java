package dev.margintrace.margin_attribution_backend.infrastructure.acceleration;

import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import java.nio.file.Path;

/**
 * Private JNI boundary. Native calls copy submitted arrays into owned storage before GPU work.
 * Libraries are loaded independently and only when their selected family is requested.
 */
final class NativeBindings {
    private NativeBindings() {}
    static String directory() {
        return System.getProperty("margintrace.native.dir", System.getenv("MARGIN_NATIVE_LIBRARY_DIR"));
    }
    static String kernels() {
        String configured = System.getProperty("margintrace.opencl.kernelDir", System.getenv("MARGIN_OPENCL_KERNEL_DIR"));
        if (configured != null) return configured;
        return directory() == null ? "." : directory();
    }
    private static void load(String family) {
        String name = "margintrace_" + family;
        if (directory() == null) System.loadLibrary(name);
        else System.load(Path.of(directory(), System.mapLibraryName(name)).toAbsolutePath().toString());
    }

    static final class Cuda {
        static { load("cuda"); }
        static native String[][] devices();
        static native int[] frontier(String device, String kernels, int[] offsets, int[] reverseOffsets,
                                     int[] successors, int[] terminals, long[] values, byte[] comparable, long threshold);
        static native long create(String device, String kernels, FgwEngine.Graph baseline, FgwEngine.Config config);
        static native FgwEngine.Result compare(long session, FgwEngine.Graph target, double[][] starts);
        static native void close(long session);
    }

    static final class OpenCl {
        static { load("opencl"); }
        static native String[][] devices();
        static native int[] frontier(String device, String kernels, int[] offsets, int[] reverseOffsets,
                                     int[] successors, int[] terminals, long[] values, byte[] comparable, long threshold);
        static native long create(String device, String kernels, FgwEngine.Graph baseline, FgwEngine.Config config);
        static native FgwEngine.Result compare(long session, FgwEngine.Graph target, double[][] starts);
        static native void close(long session);
    }
}
