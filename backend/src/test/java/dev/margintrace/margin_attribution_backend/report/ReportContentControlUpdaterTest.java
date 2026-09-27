package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportContentControlUpdaterTest {

    private final ReportContentControlUpdater updater = new ReportContentControlUpdater();
    private final ReportContentControlScanner scanner = new ReportContentControlScanner();

    @Test
    void replacesInlineControlContentByAlias() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r><w:t>Revenue: </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:id w:val="41"/>
                          <w:alias w:val="Sales revenue"/>
                          <w:tag w:val="margintrace:revenue"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:t>Old value</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>
                  </w:body>
                </w:document>
                """);

        ReportContentControlUpdater.UpdateResult result =
                updater.replaceContentByAlias(document, "Sales revenue", "1,250,000");

        assertThat(result.updatedControls()).isEqualTo(1);
        assertThat(scanner.scan(result.documentBytes()))
                .singleElement()
                .satisfies(control -> assertThat(control.preview()).isEqualTo("1,250,000"));
    }

    @Test
    void updatesOnlyControlsWithTheRequestedAlias() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:sdt>
                      <w:sdtPr><w:alias w:val="Target"/><w:tag w:val="target"/></w:sdtPr>
                      <w:sdtContent><w:p><w:r><w:t>Old target</w:t></w:r></w:p></w:sdtContent>
                    </w:sdt>
                    <w:sdt>
                      <w:sdtPr><w:alias w:val="Other"/><w:tag w:val="other"/></w:sdtPr>
                      <w:sdtContent><w:p><w:r><w:t>Keep me</w:t></w:r></w:p></w:sdtContent>
                    </w:sdt>
                  </w:body>
                </w:document>
                """);

        ReportContentControlUpdater.UpdateResult result =
                updater.replaceContentByAlias(document, "Target", "New target");

        assertThat(scanner.scan(result.documentBytes()))
                .extracting(ReportContentControlScanner.ContentControlDescriptor::preview)
                .containsExactly("New target", "Keep me");
    }

    @Test
    void rejectsAnAliasThatDoesNotExist() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body/>
                </w:document>
                """);

        assertThatThrownBy(() -> updater.replaceContentByAlias(document, "Missing", "Value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing");
    }

    @Test
    void replacesMultipleControlsAndTextTokens() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:sdt>
                        <w:sdtPr><w:alias w:val="Current Period"/><w:tag w:val="Current Period"/></w:sdtPr>
                        <w:sdtContent><w:r><w:t>Period placeholder</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>
                    <w:p>
                      <w:sdt>
                        <w:sdtPr><w:alias w:val="Revenue"/><w:tag w:val="revenue"/></w:sdtPr>
                        <w:sdtContent><w:r><w:t>Revenue placeholder</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>
                    <w:p>
                      <w:r><w:t>Text token: {{Gross Margin %}}</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """);

        java.util.Map<String, String> replacements = java.util.Map.of(
                "Current Period", "2026-08-01 ~ 2026-08-31",
                "Revenue", "¥ 12,000.00",
                "Gross Margin %", "59.2%"
        );

        ReportContentControlUpdater.UpdateResult result = updater.replaceContents(document, replacements);
        assertThat(result.updatedControls()).isEqualTo(3);

        List<ReportContentControlScanner.ContentControlDescriptor> controls = scanner.scan(result.documentBytes());
        assertThat(controls).extracting(ReportContentControlScanner.ContentControlDescriptor::preview)
                .containsExactly("2026-08-01 ~ 2026-08-31", "¥ 12,000.00");
    }

    private byte[] docxWithDocumentXml(String xml) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("word/document.xml"));
            archive.write(xml.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.finish();
            return output.toByteArray();
        }
    }
}
