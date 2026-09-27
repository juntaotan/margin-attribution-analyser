package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ReportContentControlScanner.ContentControlDescriptor;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ReportTemplateStore {

    public static final String MASTER_TEMPLATE_KEY = "report-studio/templates/master-template.docx";
    public static final String MASTER_TEMPLATE_META_KEY = "report-studio/templates/master-template.meta.json";
    public static final String DEFAULT_TEMPLATE_FILENAME = "master-management-commentary-template.docx";

    public static final List<ExpectedPlaceholder> EXPECTED_PLACEHOLDERS = List.of(
            new ExpectedPlaceholder("Current Period", "Current reporting duration"),
            new ExpectedPlaceholder("Comparison Period", "Comparable baseline duration"),
            new ExpectedPlaceholder("Revenue", "Current period sales revenue"),
            new ExpectedPlaceholder("Revenue Change %", "Revenue growth rate vs comparable period"),
            new ExpectedPlaceholder("Gross Margin", "Current gross margin contribution"),
            new ExpectedPlaceholder("Gross Margin %", "Current gross margin percentage")
    );

    private final MinioClient minioClient;
    private final ReportContentControlScanner contentControlScanner;
    private final DocxDocumentValidator docxDocumentValidator;
    private final ObjectMapper objectMapper;
    private final AtomicLong templateVersion = new AtomicLong(1L);

    @Value("${onlyoffice.report-bucket}")
    private String bucket;

    public synchronized byte[] loadOrCreateMasterTemplate() throws Exception {
        ensureBucketExists();
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(MASTER_TEMPLATE_KEY)
                .build())) {
            return input.readAllBytes();
        } catch (Exception missingTemplate) {
            byte[] initialTemplate = createDefaultMasterTemplate();
            saveMasterTemplate(initialTemplate, DEFAULT_TEMPLATE_FILENAME, false);
            return initialTemplate;
        }
    }

    public synchronized TemplateMetadata getMetadata() throws Exception {
        byte[] docxBytes = loadOrCreateMasterTemplate();
        TemplateMetaRecord meta = readMetaRecord();
        List<ContentControlDescriptor> controls = contentControlScanner.scan(docxBytes);

        List<PlaceholderItem> placeholders = new ArrayList<>();
        int matchedCount = 0;

        for (ExpectedPlaceholder expected : EXPECTED_PLACEHOLDERS) {
            boolean matched = false;
            String preview = null;
            String expectedNorm = normalize(expected.key());

            for (ContentControlDescriptor control : controls) {
                String tagNorm = normalize(control.tag());
                String aliasNorm = normalize(control.alias());
                if (expectedNorm.equals(tagNorm) || expectedNorm.equals(aliasNorm)) {
                    matched = true;
                    preview = control.preview();
                    break;
                }
            }

            if (matched) {
                matchedCount++;
            }
            placeholders.add(new PlaceholderItem(
                    expected.key(),
                    expected.label(),
                    matched,
                    preview != null ? preview : ""
            ));
        }

        return new TemplateMetadata(
                meta.filename(),
                docxBytes.length,
                meta.updatedAt(),
                meta.isCustom(),
                placeholders,
                matchedCount,
                EXPECTED_PLACEHOLDERS.size(),
                templateVersion.get()
        );
    }

    public synchronized TemplateMetadata uploadMasterTemplate(String originalFilename, byte[] content) throws Exception {
        docxDocumentValidator.validate(originalFilename, content);
        saveMasterTemplate(content, originalFilename, true);
        return getMetadata();
    }

    public synchronized TemplateMetadata resetToDefault() throws Exception {
        byte[] defaultDoc = createDefaultMasterTemplate();
        saveMasterTemplate(defaultDoc, DEFAULT_TEMPLATE_FILENAME, false);
        return getMetadata();
    }

    public long currentVersion() {
        return templateVersion.get();
    }

    private synchronized void saveMasterTemplate(byte[] content, String filename, boolean isCustom) throws Exception {
        ensureBucketExists();
        putObject(
                MASTER_TEMPLATE_KEY,
                content,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        TemplateMetaRecord meta = new TemplateMetaRecord(
                filename,
                Instant.now().toString(),
                isCustom
        );
        byte[] metaBytes = objectMapper.writeValueAsBytes(meta);
        putObject(MASTER_TEMPLATE_META_KEY, metaBytes, "application/json");

        templateVersion.incrementAndGet();
    }

    private TemplateMetaRecord readMetaRecord() {
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(MASTER_TEMPLATE_META_KEY)
                .build())) {
            return objectMapper.readValue(input.readAllBytes(), TemplateMetaRecord.class);
        } catch (Exception missingMeta) {
            return new TemplateMetaRecord(
                    DEFAULT_TEMPLATE_FILENAME,
                    Instant.now().toString(),
                    false
            );
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void putObject(String objectKey, byte[] content, String contentType) throws Exception {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    public static byte[] createDefaultMasterTemplate() throws Exception {
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:pPr>
                        <w:pStyle w:val="Title"/>
                        <w:jc w:val="center"/>
                        <w:spacing w:before="240" w:after="120"/>
                      </w:pPr>
                      <w:r>
                        <w:rPr>
                          <w:b/>
                          <w:sz w:val="44"/>
                          <w:color w:val="1E293B"/>
                        </w:rPr>
                        <w:t>Management Commentary: Gross Margin Performance</w:t>
                      </w:r>
                    </w:p>
                    <w:p>
                      <w:pPr>
                        <w:jc w:val="center"/>
                        <w:spacing w:after="360"/>
                      </w:pPr>
                      <w:r>
                        <w:rPr>
                          <w:i/>
                          <w:sz w:val="20"/>
                          <w:color w:val="64748B"/>
                        </w:rPr>
                        <w:t>Dual-BOM Multi-Attribute Reconciliation &amp; Attribution Framework</w:t>
                      </w:r>
                    </w:p>
                    
                    <w:p>
                      <w:pPr><w:spacing w:after="120"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:color w:val="334155"/></w:rPr><w:t>Executive Reporting Parameters:</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>• Current Reporting Period (Current Period): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Current Period"/>
                          <w:tag w:val="Current Period"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="2563EB"/></w:rPr><w:t>Current Period</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>
                    <w:p>
                      <w:pPr><w:spacing w:after="240"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>• Comparable Baseline Period (Comparison Period): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Comparison Period"/>
                          <w:tag w:val="Comparison Period"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="2563EB"/></w:rPr><w:t>Comparison Period</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="0F172A"/></w:rPr><w:t>1. Financial Highlights &amp; Gross Margin Overview</w:t></w:r>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>1.1 Net Sales Revenue (Revenue): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Revenue"/>
                          <w:tag w:val="Revenue"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Revenue</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>1.2 Revenue Growth Rate (Revenue Change %): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Revenue Change %"/>
                          <w:tag w:val="Revenue Change %"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Revenue Change %</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>1.3 Total Gross Margin (Gross Margin): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Gross Margin"/>
                          <w:tag w:val="Gross Margin"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Gross Margin</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="240"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>1.4 Gross Margin Rate (Gross Margin %): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Gross Margin %"/>
                          <w:tag w:val="Gross Margin %"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Gross Margin %</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="0F172A"/></w:rPr><w:t>2. Dual-BOM Root-Cause Attribution &amp; Variance Analysis</w:t></w:r>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>2.1 Purchase Price Variance (PPV Variance): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="PPV Variance"/>
                          <w:tag w:val="PPV Variance"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>PPV Variance</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>2.2 Shop-Floor Usage Deviation (Usage Variance): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Usage Variance"/>
                          <w:tag w:val="Usage Variance"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Usage Variance</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="80"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>2.3 Defect &amp; Scrap Financial Loss (Scrap Loss): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="Scrap Loss"/>
                          <w:tag w:val="Scrap Loss"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>Scrap Loss</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:after="160"/></w:pPr>
                      <w:r><w:rPr><w:color w:val="475569"/></w:rPr><w:t>2.4 Structural Design BOM Revision (ECN Impact): </w:t></w:r>
                      <w:sdt>
                        <w:sdtPr>
                          <w:alias w:val="ECN Impact"/>
                          <w:tag w:val="ECN Impact"/>
                        </w:sdtPr>
                        <w:sdtContent><w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t>ECN Impact</w:t></w:r></w:sdtContent>
                      </w:sdt>
                    </w:p>

                    <w:p>
                      <w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr>
                      <w:r><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="0F172A"/></w:rPr><w:t>3. Executive Commentary &amp; Corrective Actions</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:pPr><w:spacing w:after="120"/></w:pPr>
                      <w:r>
                        <w:rPr><w:color w:val="334155"/></w:rPr>
                        <w:t>This executive commentary is synthesized via the Dual-BOM Multi-Attribute Attribution Engine, decoupling structural bill-of-materials revisions from shop-floor material usage and procurement execution deviations. To inspect deeper component causal trees or customize narrative sections, edit directly in Report Studio or Microsoft Word.</w:t>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        String contentTypesXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """;

        String relsXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """;

        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream archive = new ZipOutputStream(output)) {

            archive.putNextEntry(new ZipEntry("[Content_Types].xml"));
            archive.write(contentTypesXml.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();

            archive.putNextEntry(new ZipEntry("_rels/.rels"));
            archive.write(relsXml.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();

            archive.putNextEntry(new ZipEntry("word/document.xml"));
            archive.write(documentXml.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();

            archive.finish();
            return output.toByteArray();
        }
    }

    public record ExpectedPlaceholder(String key, String label) {}

    public record PlaceholderItem(
            String key,
            String label,
            boolean detected,
            String samplePreview
    ) {}

    public record TemplateMetadata(
            String filename,
            long sizeBytes,
            String updatedAt,
            boolean isCustom,
            List<PlaceholderItem> detectedPlaceholders,
            int matchedCount,
            int totalExpected,
            long version
    ) {}

    private record TemplateMetaRecord(
            String filename,
            String updatedAt,
            boolean isCustom
    ) {}
}
