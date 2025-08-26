package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QueueItemsBatch(
        @Schema(description = "List of items to add to the queue")
        @NotEmpty
        @Valid
        List<QueueItemCreate> items
) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record QueueItemCreate(
            @Schema(description = "Item type", example = "trace")
            QueueItem.QueueItemType itemType,

            @Schema(description = "Item ID (trace_id or thread_id)", example = "trace_123")
            String itemId
    ) {
    }
}