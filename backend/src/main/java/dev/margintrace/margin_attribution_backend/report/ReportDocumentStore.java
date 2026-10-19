package dev.margintrace.margin_attribution_backend.report;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Stores the single Report Studio document and its title in the MinIO report bucket
 * ({@code onlyoffice.report-bucket}).
 *
 * <p>The document is created from the master template on first use. The version is an in-memory timestamp updated on
 * every write; it identifies document revisions for ONLYOFFICE document keys and resets when the application
 * restarts. The bucket is created if it does not exist.
 */
@Service
@RequiredArgsConstructor
public class ReportDocumentStore {

    private static final String DEFAULT_DOCUMENT_KEY = "report-studio/default/management-commentary.docx";
    private static final String DEFAULT_DOCUMENT_TITLE_KEY = "report-studio/default/title.txt";
    private static final String DEFAULT_DOCUMENT_TITLE = "Management Commentary.docx";

    private final MinioClient minioClient;
    private final ReportTemplateStore templateStore;
    private final AtomicLong version = new AtomicLong(System.currentTimeMillis());

    @Value("${onlyoffice.report-bucket}")
    private String bucket;

    /**
     * Loads the report document. If it cannot be read, a copy of the master template is stored and returned
     * instead.
     *
     * @return the DOCX content
     * @throws Exception if the bucket or the template cannot be accessed
     */
    public synchronized byte[] loadOrCreateDefaultDocument() throws Exception {
        ensureBucketExists();

        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(DEFAULT_DOCUMENT_KEY)
                .build())) {
            return input.readAllBytes();
        } catch (Exception missingDocument) {
            byte[] initialDocument = templateStore.loadOrCreateMasterTemplate();
            saveDefaultDocument(initialDocument);
            return initialDocument;
        }
    }

    /**
     * Overwrites the report document content, keeping its title, and advances the version.
     *
     * @param content the new DOCX content
     * @throws Exception if the object cannot be written
     */
    public synchronized void saveDefaultDocument(byte[] content) throws Exception {
        ensureBucketExists();
        putObject(
                DEFAULT_DOCUMENT_KEY,
                content,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        version.set(System.currentTimeMillis());
    }

    /**
     * Replaces the report document and its title, and advances the version.
     *
     * @param content the new DOCX content
     * @param title the file name to show in the editor and on download
     * @throws Exception if the objects cannot be written
     */
    public synchronized void replaceDefaultDocument(byte[] content, String title) throws Exception {
        ensureBucketExists();
        putObject(
                DEFAULT_DOCUMENT_KEY,
                content,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        putObject(DEFAULT_DOCUMENT_TITLE_KEY, title.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN_VALUE);
        version.set(System.currentTimeMillis());
    }

    /**
     * Replaces the report document with a fresh copy of the master template and restores the default title.
     *
     * @throws Exception if the template cannot be loaded or the document cannot be written
     */
    public synchronized void resetToMasterTemplate() throws Exception {
        byte[] masterContent = templateStore.loadOrCreateMasterTemplate();
        replaceDefaultDocument(masterContent, DEFAULT_DOCUMENT_TITLE);
    }

    /**
     * @return the stored document title, or the default title if none is stored
     * @throws Exception if the bucket cannot be accessed
     */
    public synchronized String currentTitle() throws Exception {
        ensureBucketExists();
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(DEFAULT_DOCUMENT_TITLE_KEY)
                .build())) {
            String title = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return title.isBlank() ? DEFAULT_DOCUMENT_TITLE : title;
        } catch (Exception missingTitle) {
            return DEFAULT_DOCUMENT_TITLE;
        }
    }

    /**
     * @return the version of the last write, as epoch milliseconds; initially the application start time
     */
    public long currentVersion() {
        return version.get();
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void putObject(String objectKey, byte[] content, String contentType) throws Exception {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, (long) content.length, -1L)
                            .contentType(contentType)
                            .build());
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
            introduction
                    .createRun()
                    .setText(
                            "Edit this report in ONLYOFFICE. MarginTrace placeholders will be connected in the next step.");

            document.write(output);
            return output.toByteArray();
        }
    }
}
