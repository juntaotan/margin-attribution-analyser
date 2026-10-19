package dev.margintrace.margin_attribution_backend.infrastructure.embedding;

import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings;
import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Owned warm model process using the packaged NDJSON encoder. Runtime/model paths are configurable;
 * downloads are an explicit setup operation. Response reads have bounded timeouts and cancellation
 * interrupts the wait and destroys the process. stderr is discarded to avoid leaking provider data.
 */
public final class PythonEmbeddingProvider implements EmbeddingProvider {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final long timeoutSeconds;
    private Metadata metadata;
    private int maximum;

    public record Reply(
            Metadata metadata,
            Integer maximumTokens,
            Integer tokens,
            Map<String, double[]> vectors,
            Double milliseconds,
            String error) {}

    public PythonEmbeddingProvider(
            String python,
            String script,
            String modelId,
            String revision,
            EmbeddingSettings settings,
            long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        try {
            process = new ProcessBuilder(python, script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            reader = process.inputReader(StandardCharsets.UTF_8);
            writer = process.outputWriter(StandardCharsets.UTF_8);
        } catch (IOException error) {
            reads.shutdownNow();
            throw new IllegalStateException("Embedding runtime unavailable", error);
        }
        try {
            var config = new LinkedHashMap<String, Object>();
            config.put("modelId", settings.modelId() == null ? modelId : settings.modelId());
            config.put("revision", settings.modelRevision() == null ? revision : settings.modelRevision());
            config.put("backend", settings.backend());
            config.put("device", settings.deviceId());
            config.put("allowCpuFallback", settings.allowCpuFallback());
            config.put("localOnly", true);
            var response = exchange(Map.of("operation", "init", "config", config));
            metadata = response.metadata();
            maximum = response.maximumTokens();
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public Metadata metadata() {
        return metadata;
    }

    public int maximumTokens() {
        return maximum;
    }

    public int tokenCount(String text) {
        return exchange(Map.of("operation", "tokens", "text", text)).tokens();
    }

    public Map<String, double[]> encode(List<Input> inputs) {
        return exchange(Map.of("operation", "encode", "inputs", inputs)).vectors();
    }

    private Reply exchange(Object input) {
        Future<String> response = null;
        try {
            writer.write(mapper.writeValueAsString(input));
            writer.newLine();
            writer.flush();
            response = reads.submit(reader::readLine);
            String line = response.get(timeoutSeconds, TimeUnit.SECONDS);
            if (line == null) throw new IllegalStateException("Embedding process terminated");
            Reply reply = mapper.readValue(line, Reply.class);
            if (reply.error() != null) throw new IllegalStateException(reply.error());
            return reply;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            close();
            throw new CancellationException("Embedding inference cancelled");
        } catch (TimeoutException error) {
            close();
            throw new IllegalStateException("EMBEDDING_TIMEOUT", error);
        } catch (IOException | ExecutionException error) {
            close();
            throw new IllegalStateException("Embedding provider failed", error);
        } finally {
            if (response != null) response.cancel(true);
        }
    }

    /**
     * Stops the Python process and its reader thread.
     */
    public void close() {
        process.destroyForcibly();
        reads.shutdownNow();
        try {
            writer.close();
            reader.close();
        } catch (IOException ignored) {
            /* Process already terminated. */
        }
    }
}
