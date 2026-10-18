package dev.margintrace.margin_attribution_backend.investigation;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Model calls for the investigation report. Both calls receive only computed facts and retrieved sources, and every
 * generated item must cite them; {@link InvestigationService} removes items whose citations do not check out.
 */
public interface InvestigationAssistant {

    @SystemMessage("""
            You are a manufacturing cost investigation assistant. Answer in English.
            Use only the numbered facts [F1], [F2], ... and numbered sources [S1], [S2], ... supplied.
            Return:
            - context: at most five items on what the sources say that is relevant to this anomaly, each at most
              two sentences. Each item must cite at least one source ID such as "S1". Do not restate the facts.
            - hypotheses: at most three possible causes, most likely first, each at most two sentences. Each must
              cite the fact and/or source IDs it rests on and give nextCheck: one sentence naming a concrete check
              (records, place or people) that would confirm or reject it.
            Never invent figures, documents, orders or events. Do not present a hypothesis as confirmed.
            Return empty lists when the evidence does not support any item.
            """)
    @UserMessage("""
            Anomaly: {{anomaly}}

            Verified facts:
            {{facts}}

            Retrieved sources:
            {{sources}}
            """)
    Findings findings(@V("anomaly") String anomaly, @V("facts") String facts, @V("sources") String sources);

    @SystemMessage("""
            You are a manufacturing cost investigation assistant. Write a 3-5 sentence executive summary in
            English: what changed and by how much, the leading hypotheses and what to verify first. Use only the
            supplied facts and findings. Never invent figures and do not present a hypothesis as confirmed.
            """)
    @UserMessage("""
            Anomaly: {{anomaly}}

            Verified facts:
            {{facts}}

            Validated findings:
            {{findings}}
            """)
    String summary(@V("anomaly") String anomaly, @V("facts") String facts, @V("findings") String findings);

    /** Structured model output before citation validation. */
    record Findings(List<Context> context, List<Hypothesis> hypotheses) { }

    record Context(String text, List<String> citations) { }

    record Hypothesis(String text, List<String> citations, String nextCheck) { }
}
