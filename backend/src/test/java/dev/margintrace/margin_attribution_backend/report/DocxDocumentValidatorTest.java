package dev.margintrace.margin_attribution_backend.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DocxDocumentValidatorTest {

    private final DocxDocumentValidator validator = new DocxDocumentValidator();

    @Test
    void acceptsAValidDocxDocument() throws Exception {
        byte[] document;
        try (XWPFDocument wordDocument = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            wordDocument.createParagraph().createRun().setText("Uploaded report");
            wordDocument.write(output);
            document = output.toByteArray();
        }

        assertThatNoException().isThrownBy(() -> validator.validate("report.docx", document));
    }

    @Test
    void rejectsAFileWithTheWrongExtension() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                validator.validate("report.xlsx", new byte[]{1}));
    }

    @Test
    void rejectsInvalidDocxContent() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                validator.validate("report.docx", "not a zip".getBytes()));
    }
}
