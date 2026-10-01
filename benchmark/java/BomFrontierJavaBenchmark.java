import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomUpwardEngine;
import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomUpwardGraph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Runs the Java frontier engine against the shared binary benchmark fixture. */
public final class BomFrontierJavaBenchmark {
    private BomFrontierJavaBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: BomFrontierJavaBenchmark <dataset> <iterations> <threshold> <warmups>");
            System.exit(1);
        }

        Path dataset = Path.of(args[0]);
        int iterations = Integer.parseInt(args[1]);
        long threshold = Long.parseUnsignedLong(args[2]);
        int warmups = Integer.parseInt(args[3]);

        long loadStart = System.nanoTime();
        BomUpwardGraph graph = new BomUpwardGraph(
                readInts(dataset.resolve("offsets.bin")),
                readInts(dataset.resolve("reverse_offsets.bin")),
                readInts(dataset.resolve("reverse_successors.bin")),
                readInts(dataset.resolve("terminal_nodes.bin")),
                readLongs(dataset.resolve("node_values.bin")),
                readBytes(dataset.resolve("node_comparable.bin")));
        double loadMs = milliseconds(System.nanoTime() - loadStart);

        BomUpwardEngine engine = new BomUpwardEngine();
        for (int warmup = 0; warmup < warmups; warmup++) {
            engine.run(graph, threshold);
        }

        double[] input = new double[iterations];
        double[] compute = new double[iterations];
        double[] output = new double[iterations];
        double[] total = new double[iterations];
        int[] result = new int[0];
        for (int iteration = 0; iteration < iterations; iteration++) {
            BomUpwardEngine.ProfiledRun run = engine.profile(graph, threshold);
            result = run.nodes();
            input[iteration] = run.timings().inputMs();
            compute[iteration] = run.timings().computeMs();
            output[iteration] = run.timings().outputMs();
            total[iteration] = run.timings().totalMs();
        }

        long sum = 0;
        long xor = 0;
        for (int node : result) {
            long mixed = mix(Integer.toUnsignedLong(node));
            sum += mixed;
            xor ^= mixed;
        }

        String device = System.getProperty("java.vm.name") + " "
                + System.getProperty("java.runtime.version");
        System.out.printf("BENCHMARK_JSON {\"backend\":\"Java\",\"device\":\"%s\","
                        + "\"load_ms\":%.6f,\"nodes\":%d,\"edges\":%d,\"terminals\":%d,"
                        + "\"threshold\":\"%s\",\"input_ms\":%s,\"compute_ms\":%s,"
                        + "\"output_ms\":%s,\"total_ms\":%s,\"result_count\":%d,"
                        + "\"result_sum\":\"%s\",\"result_xor\":\"%s\"}%n",
                device.replace("\"", "\\\""), loadMs, graph.nodeValues().length,
                graph.reverseSuccessors().length, graph.terminalNodes().length,
                Long.toUnsignedString(threshold), numbers(input), numbers(compute), numbers(output),
                numbers(total), result.length, Long.toUnsignedString(sum), Long.toUnsignedString(xor));
    }

    private static int[] readInts(Path path) throws IOException {
        ByteBuffer bytes = mapped(path);
        int[] values = new int[bytes.remaining() / Integer.BYTES];
        bytes.asIntBuffer().get(values);
        return values;
    }

    private static long[] readLongs(Path path) throws IOException {
        ByteBuffer bytes = mapped(path);
        long[] values = new long[bytes.remaining() / Long.BYTES];
        bytes.asLongBuffer().get(values);
        return values;
    }

    private static byte[] readBytes(Path path) throws IOException {
        ByteBuffer bytes = mapped(path);
        byte[] values = new byte[bytes.remaining()];
        bytes.get(values);
        return values;
    }

    /** Maps one fixture array without creating an intermediate byte array. */
    private static ByteBuffer mapped(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static String numbers(double[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index != 0) result.append(',');
            result.append(String.format(java.util.Locale.ROOT, "%.6f", values[index]));
        }
        return result.append(']').toString();
    }

    private static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double milliseconds(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }
}
