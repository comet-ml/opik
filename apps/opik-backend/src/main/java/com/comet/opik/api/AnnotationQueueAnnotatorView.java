package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueAnnotatorView(
        @Schema(description = "Path inside each trace's input shown as the user message, e.g. messages[-1].content") @Nullable @Size(max = 1000) String input,
        @Schema(description = "Path inside each trace's output shown as the agent message, e.g. choices[0].message.content") @Nullable @Size(max = 1000) String output,
        @Schema(description = "Trace queues: the blocks annotators see, in order. Absent means the default blocks") @Nullable @Size(max = 50) List<@NotNull @Valid Field> fields) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Field(
            @Schema(description = "Block title shown to annotators") @NotBlank @Size(max = 150) String label,
            @Schema(description = "Path from the trace root, e.g. input.messages[-1].content, metadata.locale, attachments or feedback_scores.accuracy") @NotBlank @Size(max = 1000) String path,
            @Schema(description = "Whether the block takes half the width, so two blocks sit side by side") @Nullable Boolean halfWidth) {
    }
}
