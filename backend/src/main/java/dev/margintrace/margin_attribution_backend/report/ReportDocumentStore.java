package dev.margintrace.margin_attribution_backend.report;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class ReportDocumentStore {

    private static final String DEFAULT_DOCUMENT_KEY = "report-studio/default/management-commentary.docx";

    private final MinioClient minioClient;
    private final AtomicLong version = new AtomicLong(1L);

    @Value("${onlyoffice.report-bucket}")
    private String bucket;

    public synchronized byte[] loadOrCreateDefaultDocument() throws Exception {
        ensureBucketExists();

        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(DEFAULT_DOCUMENT_KEY)
                .build())) {
            return input.readAllBytes();
        } catch (Exception missingDocument) {
            byte[] initialDocument = createInitialDocument();
            saveDefaultDocument(initialDocument);
            return initialDocument;
        }
    }

    public synchronized void saveDefaultDocument(byte[] content) throws Exception {
        ensureBucketExists();
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(DEFAULT_DOCUMENT_KEY)
                    .stream(input, (long) content.length, -1L)
                    .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .build());
        }
        version.incrementAndGet();
    }

    public long currentVersion() {
        return version.get();
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private byte[] createInitialDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Title");
            XWPFRun titleRun = title.createRun();
            titleRun.setText("Management Commentary");

            XWPFParagraph introduction = document.createParagraph();
            introduction.createRun().setText(
                    "Edit this report in ONLYOFFICE. MarginTrace placeholders will be connected in the next step.");

            document.write(output);
            return output.toByteArray();
        }
    }
}
