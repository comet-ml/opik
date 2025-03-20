package com.comet.opik.api.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompleteMultipartUploadRequest(
        @NotBlank String fileName,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        @NotNull EntityType entityType,
        @NotNull UUID entityId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID containerId,
        @NotNull Long fileSize,
        String mimeType,
        @NotBlank String uploadId,
        @NotNull List<MultipartUploadPart> uploadedFileParts) implements AttachmentInfoHolder {
}
