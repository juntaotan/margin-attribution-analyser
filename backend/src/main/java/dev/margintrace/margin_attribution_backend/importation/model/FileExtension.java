package dev.margintrace.margin_attribution_backend.importation.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

@Getter
public enum FileExtension {
    XLSX("xlsx"),
    XLS("xls");

    private final String value;

    FileExtension(String value){
        this.value = value;
    }

    public static boolean contains (String extension){
        return from(extension).isPresent();
    }

    public static Optional<FileExtension> from(String extension) {
        if (extension == null || extension.isBlank()){
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type ->
                        type.value.equalsIgnoreCase(extension.trim())
                )
                .findFirst();
    }
}
