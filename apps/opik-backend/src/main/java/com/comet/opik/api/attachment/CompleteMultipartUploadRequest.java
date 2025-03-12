package com.comet.opik.api.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompleteMultipartUploadRequest(
        @NotBlank String fileName,
        @NotBlank String mimeType,
        @NotNull EntityType entityType,
        @NotNull UUID entityId,
        @NotNull UUID containerId,
        @NotNull Long fileSize,
        @NotBlank String uploadId,
        @NotNull List<MultipartUploadPart> uploadedFileParts) implements AttachmentInfoHolder {
}
