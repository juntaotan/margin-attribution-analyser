package dev.margintrace.margin_attribution_backend.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs the local embedding runtime (Python virtual environment, PyTorch and
 * sentence-transformers) and downloads the configured pinned model on explicit user request.
 *
 * <p>The packaged {@code install.sh} runs in the configured runtime directory, which should be a
 * persistent volume. The CPU variant installs PyTorch from download.pytorch.org; the CUDA variant
 * installs the default PyPI build. The model is downloaded from huggingface.co and then loaded by
 * the same encoder used for analysis. Only one installation runs at a time. Installation state is
 * kept in memory while a job runs or after it fails; a successful installation is recognised from
 * the {@code installed.json} marker, so it survives backend restarts. Analysis uses the runtime
 * through the existing {@code margin.embedding.*} properties, whose defaults point to this directory.</p>
 */
@Service
public class EmbeddingRuntimeService {
    /** Installation status; {@code state} is NOT_INSTALLED, INSTALLING, READY or FAILED. */
    public record Status(
            String state,
            String variant,
            String stage,
            String message,
            String modelId,
            String revision,
            Boolean cudaAvailable) {}

    private record Installed(String variant, String modelId, String revision, boolean cudaAvailable) {}

    private static final List<String> RESOURCES = List.of("install.sh", "requirements.txt", "sentence_encoder.py");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;
    private final String model;
    private final String revision;
    private volatile Status job;

    public EmbeddingRuntimeService(
            @Value("${margin.embedding.runtime-dir:/var/lib/margintrace/embedding}") String dir,
            @Value("${margin.embedding.model:}") String model,
            @Value("${margin.embedding.revision:}") String revision) {
        this.dir = Path.of(dir);
        this.model = model;
        this.revision = revision;
    }

    /**
     * Return the running or failed job, otherwise READY when the marker matches the configured
     * model and revision, otherwise NOT_INSTALLED.
     */
    public Status status() {
        Status current = job;
        if (current != null) return current;
        Installed installed = installed();
        if (installed != null
                && installed.modelId().equals(model)
                && installed.revision().equals(revision))
            return new Status("READY", installed.variant(), null, null, model, revision, installed.cudaAvailable());
        return new Status(
                "NOT_INSTALLED",
                null,
                null,
                installed == null ? null : "The installed model differs from the configured model",
                model,
                revision,
                null);
    }

    /**
     * Start an asynchronous installation of the requested variant.
     *
     * @param variant {@code cpu} or {@code cuda}
     * @return the initial INSTALLING status
     * @throws IllegalArgumentException for an unknown variant or an unconfigured model
     * @throws IllegalStateException when an installation is already running or the scripts cannot be prepared
     */
    public synchronized Status install(String variant) {
        if (!"cpu".equals(variant) && !"cuda".equals(variant))
            throw new IllegalArgumentException("Variant must be cpu or cuda");
        if (model.isBlank() || revision.isBlank())
            throw new IllegalArgumentException("Configure margin.embedding.model and margin.embedding.revision");
        if (job != null && job.state().equals("INSTALLING"))
            throw new IllegalStateException("An installation is already running");
        List<String> command;
        try {
            command = command(variant);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to prepare " + dir + ": " + error.getMessage(), error);
        }
        job = new Status("INSTALLING", variant, "Starting", null, model, revision, null);
        Thread.ofVirtual().start(() -> run(variant, command));
        return job;
    }

    /** Copy the packaged scripts into the runtime directory and build the installer command; overridable in tests. */
    protected List<String> command(String variant) throws IOException {
        Files.createDirectories(dir);
        for (String name : RESOURCES) {
            try (InputStream resource = getClass().getResourceAsStream("/embedding/" + name)) {
                Files.copy(resource, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return List.of(
                "sh",
                dir.resolve("install.sh").toString(),
                variant,
                dir.toString(),
                dir.resolve("requirements.txt").toString(),
                dir.resolve("sentence_encoder.py").toString(),
                model,
                revision);
    }

    /** Follow STAGE lines; on failure keep the last output lines as the message. */
    private void run(String variant, List<String> command) {
        Deque<String> tail = new ArrayDeque<>();
        String stage = "Starting";
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.startsWith("STAGE ")) {
                        stage = line.substring(6);
                        job = new Status("INSTALLING", variant, stage, null, model, revision, null);
                    } else {
                        tail.addLast(line);
                        if (tail.size() > 20) tail.removeFirst();
                    }
                }
            }
            int exit = process.waitFor();
            job = exit == 0
                    ? null
                    : new Status(
                            "FAILED",
                            variant,
                            stage,
                            tail.isEmpty() ? "Installer exited with code " + exit : String.join("\n", tail),
                            model,
                            revision,
                            null);
        } catch (IOException | InterruptedException error) {
            job = new Status("FAILED", variant, stage, error.getMessage(), model, revision, null);
        }
    }

    private Installed installed() {
        Path marker = dir.resolve("installed.json");
        if (!Files.isRegularFile(marker)) return null;
        try {
            return mapper.readValue(marker.toFile(), Installed.class);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
