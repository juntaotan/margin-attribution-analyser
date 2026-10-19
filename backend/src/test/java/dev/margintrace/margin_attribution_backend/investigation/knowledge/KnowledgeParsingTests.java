package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class KnowledgeParsingTests {

    @Test
    void csvRowsBecomeSelfDescribingPassages() {
        var rows = KnowledgeIngestionService.csvRows("\uFEFFrecord,material,finding\r\n"
                + "QR-1,MAT-10023,\"Thickness 12% above spec, line 2\"\n"
                + "QR-2,MAT-20001,\"Operator said \"\"ok\"\"\"\n"
                + "\n"
                + "QR-3,,\n");

        assertThat(rows)
                .containsExactly(
                        "record: QR-1; material: MAT-10023; finding: Thickness 12% above spec, line 2",
                        "record: QR-2; material: MAT-20001; finding: Operator said \"ok\"", "record: QR-3");
    }

    @Test
    void docxAndPlainTextAreExtracted() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("ECN-2026-014 replaces MAT-10023 with MAT-10024.");
            document.write(bytes);
        }

        assertThat(KnowledgeIngestionService.passages("change.docx", new ByteArrayInputStream(bytes.toByteArray())))
                .singleElement()
                .asString()
                .contains("replaces MAT-10023 with MAT-10024");
        assertThat(KnowledgeIngestionService.passages(
                        "notes.MD", new ByteArrayInputStream("# Note".getBytes(StandardCharsets.UTF_8))))
                .containsExactly("# Note");
    }

    @Test
    void unsupportedFormatIsRejected() {
        assertThatThrownBy(() -> KnowledgeIngestionService.passages("scan.pdf", new ByteArrayInputStream(new byte[1])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".docx, .txt, .md and .csv");
    }

    @Test
    void candidateCodesNeedADigitOrHyphen() {
        assertThat(KnowledgeIngestionService.candidateCodes(
                        "Order WO-118 used MAT-STEEL and 4711, see report. Steel ok."))
                .containsExactly("WO-118", "MAT-STEEL", "4711");
    }

    @Test
    void reciprocalRankFusionRewardsAgreement() {
        Map<String, HybridKnowledgeRetriever.Method> fused =
                HybridKnowledgeRetriever.fuse(List.of("a", "b", "c"), List.of("c", "d"), 3);

        // c: 1/63 + 1/61 beats a: 1/61; d: 1/62 ties b: 1/62 and b was seen first.
        assertThat(fused.keySet()).containsExactly("c", "a", "b");
        assertThat(fused)
                .containsEntry("c", HybridKnowledgeRetriever.Method.BOTH)
                .containsEntry("a", HybridKnowledgeRetriever.Method.DENSE);
        assertThat(HybridKnowledgeRetriever.fuse(List.of(), List.of("x"), 3))
                .containsExactly(Map.entry("x", HybridKnowledgeRetriever.Method.EXACT));
    }
}
