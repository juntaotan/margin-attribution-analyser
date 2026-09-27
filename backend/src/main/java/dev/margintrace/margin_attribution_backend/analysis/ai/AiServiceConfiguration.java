package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import dev.margintrace.margin_attribution_backend.report.ai.ReportPeriodAssistant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AiServiceConfiguration {
    private final String baseUrl;
    private final String modelName;

    public AiServiceConfiguration(
            @Value("${analysis.llama.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${analysis.llama.model:local}") String modelName) {
        String normalized = baseUrl.replaceAll("/+$", "");
        this.baseUrl = normalized.endsWith("/v1") ? normalized : normalized + "/v1";
        this.modelName = modelName;
    }

    @Bean
    RootCauseAssistant rootCauseAssistant() {
        return AiServices.create(RootCauseAssistant.class, chatModel(384, false));
    }

    @Bean
    ReportPeriodAssistant reportPeriodAssistant() {
        return AiServices.create(ReportPeriodAssistant.class, chatModel(128, true));
    }

    @Bean
    ReportBlueprintAssistant reportBlueprintAssistant() {
        return AiServices.create(ReportBlueprintAssistant.class, chatModel(900, true));
    }

    private ChatModel chatModel(int maxOutputTokens, boolean structuredOutput) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("no-key")
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(maxOutputTokens)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(0);
        if (structuredOutput) {
            builder.supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                    .strictJsonSchema(true);
        }
        return new LlamaLoadingRetryChatModel(builder.build());
    }
}
