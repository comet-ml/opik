package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.SequencedSet;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Batch of annotation queues to create")
public record AnnotationQueueBatch(
        @JsonView( {
                AnnotationQueue.View.Write.class}) @NotNull @Size(min = 1, max = 1000, message = "Batch size must be between 1 and 1000") @Valid @Schema(description = "List of annotation queues to create") SequencedSet<AnnotationQueue> annotationQueues){
}
