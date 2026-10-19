package dev.margintrace.margin_attribution_backend.datalake.key;

import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generates object keys for uploaded raw files.
 */
@Component
public class RawObjectKeyFactory {

    /**
     * @param jobId the import job the file belongs to
     * @param extension the file type
     * @return {@code raw/source=manual-upload/ingest_date=<UTC date>/<jobId>/<random UUID>.<extension>}
     */
    public String create(Long jobId, FileExtension extension) {

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Generate a unique object key with the specified format, including the current date, job ID, a random UUID,
        // and the file extension
        return "raw/source=manual-upload/ingest_date=%s/%d/%s.%s"
                .formatted(today, jobId, UUID.randomUUID(), extension.name().toLowerCase());
    }
}
