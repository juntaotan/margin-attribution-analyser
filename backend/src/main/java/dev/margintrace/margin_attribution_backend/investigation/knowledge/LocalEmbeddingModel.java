package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings;
import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider;
import dev.margintrace.margin_attribution_backend.infrastructure.embedding.PythonEmbeddingProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * LangChain4j view of the project's local {@link EmbeddingProvider}, used to embed knowledge chunks and queries.
 *
 * <p>The provider process starts lazily on first use and is reused afterwards. A start-up or inference failure
 * closes the provider and is rethrown as {@link IllegalStateException}; it is not cached, so installing the runtime
 * from Settings takes effect on the next call. Calls are serialised because the provider owns one process.</p>
 *
 * <p>Token counts come from the model's own tokenizer, so the document splitter bounds chunks exactly.</p>
 */
public class LocalEmbeddingModel implements EmbeddingModel, TokenCountEstimator, AutoCloseable {
    private final Supplier<EmbeddingProvider> factory;
    private EmbeddingProvider provider;

    /** @param factory starts a provider; called again after a failure */
    public LocalEmbeddingModel(Supplier<EmbeddingProvider> factory) {
        this.factory = factory;
    }

    /**
     * Starts the packaged Python sentence encoder on CPU, as the analysis workflow does.
     *
     * @throws IllegalStateException if the runtime is not configured or cannot start
     */
    public static EmbeddingProvider startPython(String python, String model, String revision, long timeoutSeconds) {
        if (python.isBlank() || model.isBlank() || revision.isBlank()) {
            throw new IllegalStateException("Configure margin.embedding.python/model/revision and install the embedding runtime");
        }
        if (!Files.isExecutable(Path.of(python))) {
            throw new IllegalStateException("Embedding runtime is not installed; install it from Settings");
        }
        Path script = null;
        try (InputStream resource = LocalEmbeddingModel.class.getResourceAsStream("/embedding/sentence_encoder.py")) {
            script = Files.createTempFile("margin-encoder-", ".py");
            Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
            return new PythonEmbeddingProvider(python, script.toString(), model, revision, EmbeddingSettings.cpu(), timeoutSeconds);
        } catch (IOException error) {
            throw new IllegalStateException("Embedding runtime unavailable", error);
        } finally {
            if (script != null) try { Files.deleteIfExists(script); }
            catch (IOException ignored) { /* The provider owns its already-started process. */ }
        }
    }

    /** Identifies the vector space; vectors from different signatures are not comparable. */
    public synchronized String signature() {
        return call(() -> running().metadata().signature());
    }

    @Override
    public synchronized Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<EmbeddingProvider.Input> inputs = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) inputs.add(new EmbeddingProvider.Input(Integer.toString(i), segments.get(i).text()));
        Map<String, double[]> vectors = call(() -> running().encode(inputs));
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            double[] vector = vectors.get(Integer.toString(i));
            float[] values = new float[vector.length];
            for (int j = 0; j < vector.length; j++) values[j] = (float) vector[j];
            embeddings.add(Embedding.from(values));
        }
        return Response.from(embeddings);
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return Response.from(embedAll(List.of(segment)).content().get(0));
    }

    @Override
    public Response<Embedding> embed(String text) {
        return embed(TextSegment.from(text));
    }

    @Override
    public synchronized int estimateTokenCountInText(String text) {
        return call(() -> running().tokenCount(text));
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        throw new UnsupportedOperationException("Only plain text is embedded");
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        throw new UnsupportedOperationException("Only plain text is embedded");
    }

    @Override
    public synchronized void close() {
        if (provider != null) provider.close();
        provider = null;
    }

    private EmbeddingProvider running() {
        if (provider == null) provider = factory.get();
        return provider;
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            close();
            throw error instanceof IllegalStateException state ? state
                    : new IllegalStateException("Embedding runtime failed: " + error.getMessage(), error);
        }
    }
}
