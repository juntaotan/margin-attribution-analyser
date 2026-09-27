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
