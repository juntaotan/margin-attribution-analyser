package dev.margintrace.margin_attribution_backend.importation.port;

import org.springframework.web.multipart.MultipartFile;

public interface RawFileStorage {
    StoredObject store(
            MultipartFile file,
            String objectKey
    ) throws Exception;
}
