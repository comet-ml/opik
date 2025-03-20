package com.comet.opik.api.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AttachmentInfo(
        @NonNull String fileName,
        String projectName,
        @NonNull EntityType entityType,
        @NonNull UUID entityId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID containerId,
        String mimeType) implements AttachmentInfoHolder {
}
