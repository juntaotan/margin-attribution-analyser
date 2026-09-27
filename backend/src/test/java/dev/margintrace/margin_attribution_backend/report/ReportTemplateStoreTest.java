package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ReportTemplateStoreTest {

    private final DocxDocumentValidator validator = new DocxDocumentValidator();
    private final ReportContentControlScanner scanner = new ReportContentControlScanner();

    @Test
    void defaultMasterTemplateIsValidDocxAndContainsAllSixPlaceholders() throws Exception {
        byte[] docxBytes = ReportTemplateStore.createDefaultMasterTemplate();

        assertThatNoException().isThrownBy(() ->
                validator.validate("master-template.docx", docxBytes));

        List<ReportContentControlScanner.ContentControlDescriptor> controls = scanner.scan(docxBytes);

        List<String> tags = controls.stream().map(ReportContentControlScanner.ContentControlDescriptor::tag).toList();
        List<String> aliases = controls.stream().map(ReportContentControlScanner.ContentControlDescriptor::alias).toList();

        assertThat(tags).contains(
                "Current Period",
                "Comparison Period",
                "Revenue",
                "Revenue Change %",
                "Gross Margin",
                "Gross Margin %"
        );

        assertThat(aliases).contains(
                "Current Period",
                "Comparison Period",
                "Revenue",
                "Revenue Change %",
                "Gross Margin",
                "Gross Margin %"
        );
    }
}
