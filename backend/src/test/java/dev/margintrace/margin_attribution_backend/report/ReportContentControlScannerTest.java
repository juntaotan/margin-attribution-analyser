package dev.margintrace.margin_attribution_backend.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportContentControlScannerTest {

    private final ReportContentControlScanner scanner = new ReportContentControlScanner();

    @Test
    void scansAliasesAndGroupsControlsWithTheSameTag() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:sdt>
                      <w:sdtPr>
                        <w:id w:val="41"/>
                        <w:alias w:val="Sales revenue"/>
                        <w:tag w:val="margintrace:revenue"/>
                      </w:sdtPr>
                      <w:sdtContent><w:p><w:r><w:t>1,250,000</w:t></w:r></w:p></w:sdtContent>
                    </w:sdt>
                    <w:sdt>
                      <w:sdtPr>
                        <w:id w:val="42"/>
                        <w:alias w:val="Sales revenue copy"/>
                        <w:tag w:val="margintrace:revenue"/>
                      </w:sdtPr>
                      <w:sdtContent><w:p><w:r><w:t>Summary value</w:t></w:r></w:p></w:sdtContent>
                    </w:sdt>
                  </w:body>
                </w:document>
                """);

        List<ReportContentControlScanner.ContentControlDescriptor> controls = scanner.scan(document);

        assertThat(controls).singleElement().satisfies(control -> {
            assertThat(control.id()).isEqualTo("margintrace:revenue");
            assertThat(control.alias()).isEqualTo("Sales revenue");
            assertThat(control.preview()).isEqualTo("1,250,000");
            assertThat(control.occurrences()).isEqualTo(2);
            assertThat(control.tagged()).isTrue();
        });
    }

    @Test
    void usesTheWordIdWhenAControlHasAnAliasButNoTag() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:sdt>
                      <w:sdtPr>
                        <w:id w:val="99"/>
                        <w:alias w:val="Unmapped metric"/>
                      </w:sdtPr>
                      <w:sdtContent><w:p><w:r><w:t>Select a value</w:t></w:r></w:p></w:sdtContent>
                    </w:sdt>
                  </w:body>
                </w:document>
                """);

        List<ReportContentControlScanner.ContentControlDescriptor> controls = scanner.scan(document);

        assertThat(controls).singleElement().satisfies(control -> {
            assertThat(control.id()).isEqualTo("content-control:99");
            assertThat(control.wordId()).isEqualTo("99");
            assertThat(control.alias()).isEqualTo("Unmapped metric");
            assertThat(control.tagged()).isFalse();
        });
    }

    @Test
    void extractsTheSentenceContainingTheRequestedTag() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r><w:t>Introductory sentence. </w:t></w:r>
                      <w:r><w:t>For August 2026, revenue was </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:id w:val="77"/>
                          <w:alias w:val="Revenue"/>
                          <w:tag w:val="margintrace:revenue"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:t>1,250,000</w:t></w:r></w:sdtContent>
                      </w:sdt>
                      <w:r><w:t> compared with July 2026. Closing sentence.</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """);

        assertThat(scanner.contextsForTag(document, "margintrace:revenue"))
                .containsExactly(
                        "For August 2026, revenue was "
                                + "[[TARGET tag=\"margintrace:revenue\" alias=\"Revenue\" value=\"1,250,000\"]] "
                                + "compared with July 2026.");
    }

    @Test
    void marksOnlyTheRequestedControlAsTheTargetWhenTwoTagsShareASentence() throws Exception {
        byte[] document = docxWithDocumentXml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r><w:t>During January 2026, the company recorded total revenue of </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Revenue"/>
                          <w:tag w:val="revenue"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:t>Revenue</w:t></w:r></w:sdtContent>
                      </w:sdt>
                      <w:r><w:t>, compared with </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Comparison Revenue"/>
                          <w:tag w:val="comparison_revenue"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:t>Comparison Revenue</w:t></w:r></w:sdtContent>
                      </w:sdt>
                      <w:r><w:t> in January 2025.</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """);

        assertThat(scanner.contextsForTag(document, "revenue"))
                .singleElement()
                .asString()
                .contains("[[TARGET tag=\"revenue\"")
                .contains("[[CONTROL tag=\"comparison_revenue\"");
        assertThat(scanner.contextsForTag(document, "comparison_revenue"))
                .singleElement()
                .asString()
                .contains("[[CONTROL tag=\"revenue\"")
                .contains("[[TARGET tag=\"comparison_revenue\"");
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
