package dev.margintrace.margin_attribution_backend.importation.model;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;

/**
 * Spreadsheet file types accepted for import.
 */
@Getter
public enum FileExtension {
    XLSX("xlsx"),
    XLS("xls");

    private final String value;

    FileExtension(String value) {
        this.value = value;
    }

    /**
     * @return whether the extension, without a dot and in any case, is supported
     */
    public static boolean contains(String extension) {
        return from(extension).isPresent();
    }

    /**
     * @param extension an extension without a dot, in any case
     * @return the matching type, or empty if the extension is blank or not supported
     */
    public static Optional<FileExtension> from(String extension) {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(extension.trim()))
                .findFirst();
    }
}
