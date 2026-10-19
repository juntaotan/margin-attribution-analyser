package dev.margintrace.margin_attribution_backend.analysis.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AI service that writes a short English summary of a calculated root-cause classification. The model
 * may only use the supplied classification and evidence; the numbers themselves come from the backend.
 */
public interface RootCauseAssistant {

    /**
     * @param category the calculated classification
     * @param evidence the evidence lines supporting it
     * @return a two- to three-sentence summary
     */
    @SystemMessage(
            """
            You are a manufacturing cost analysis assistant. Write a concise, 2-3 sentence analysis
            report summary in English only. Use only the supplied calculated classification and evidence.
            Do not change the classification, invent work orders, process faults or figures, or present
            an inference as confirmed. Keep unit costs separate from total cost differences.
            If the classification is Unit cost change, explicitly state that overuse and material
            substitution are not established.
            """)
    @UserMessage(
            """
            Classification: {{category}}
            Evidence:
            {{evidence}}
            """)
    String summarize(@V("category") String category, @V("evidence") String evidence);
}
