package com.comet.opik.api.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StartMultipartUploadRequest(
        @NotBlank String fileName,
        @NotBlank String mimeType,
        @NotNull Integer numOfFileParts,
        @NotNull EntityType entityType,
        @NotNull UUID entityId,
        @NotNull UUID containerId) implements AttachmentInfoHolder {
}
