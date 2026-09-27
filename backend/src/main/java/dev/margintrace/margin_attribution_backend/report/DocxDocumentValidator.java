package dev.margintrace.margin_attribution_backend.report;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;

@Component
public class DocxDocumentValidator {

    public void validate(String fileName, byte[] content) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The selected DOCX file is empty");
        }

        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(content))) {
            // Opening the OOXML package verifies that it contains a valid Word document part.
        } catch (Exception exception) {
            throw new IllegalArgumentException("The selected file is not a valid DOCX document", exception);
        }
    }
}
