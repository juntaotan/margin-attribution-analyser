package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import dev.margintrace.margin_attribution_backend.report.ai.ReportPeriodAssistant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiServiceConfiguration {

    @Bean
    RootCauseAssistant rootCauseAssistant(AiRuntimeManager runtimeManager) {
        return (category, evidence) -> runtimeManager.current()
                .rootCauseAssistant().summarize(category, evidence);
    }

    @Bean
    ReportPeriodAssistant reportPeriodAssistant(AiRuntimeManager runtimeManager) {
        return documentText -> runtimeManager.current()
                .reportPeriodAssistant().extract(documentText);
    }

    @Bean
    ReportBlueprintAssistant reportBlueprintAssistant(AiRuntimeManager runtimeManager) {
        return prompt -> runtimeManager.current()
                .reportBlueprintAssistant().generate(prompt);
    }
}
