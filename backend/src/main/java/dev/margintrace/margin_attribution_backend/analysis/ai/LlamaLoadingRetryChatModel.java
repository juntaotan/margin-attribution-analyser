package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Set;

final class LlamaLoadingRetryChatModel implements ChatModel {
    private final ChatModel delegate;

    LlamaLoadingRetryChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                return delegate.chat(request);
            } catch (RuntimeException exception) {
                HttpException http = findCause(exception, HttpException.class);
                boolean loading = http != null
                        && http.statusCode() == 503
                        && http.getMessage().contains("Loading model");
                if (!loading || attempt == 14) throw exception;
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("llama.cpp request was interrupted", interrupted);
                }
            }
        }
        throw new IllegalStateException("llama.cpp retry loop ended unexpectedly");
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }
}
