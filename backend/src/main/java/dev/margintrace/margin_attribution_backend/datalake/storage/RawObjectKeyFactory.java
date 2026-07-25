package dev.margintrace.margin_attribution_backend.datalake.storage;

import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class RawObjectKeyFactory {

    public String create(Long jobId, FileExtension extension) {
        
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Generate a unique object key with the specified format, including the current date, job ID, a random UUID, and the file extension
        return "raw/source=manual-upload/ingest_date=%s/%d/%s.%s"
                .formatted(today, jobId, UUID.randomUUID(), extension.name().toLowerCase());
    }
}
