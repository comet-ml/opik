package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueAnnotatorView(
        @Schema(description = "A custom HTML view rendered in a sandboxed iframe instead of the default trace blocks or conversation") @Nullable @Size(max = 500_000) String html,
        @Schema(description = "Version of the view kit the html was written for") @Nullable @Min(1) @Max(LATEST_KIT_VERSION) Integer kitVersion,
        @Schema(description = "What annotators see in the view, in one or two sentences") @Nullable @Size(max = 1000) String summary) {

    public static final int LATEST_KIT_VERSION = 1;
}
