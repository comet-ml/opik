package com.comet.opik.api.annotationqueue;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.filter.Operator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * One trace or thread filter, in the same shape the traces and threads endpoints take. The field is a plain
 * string because which fields exist depends on the queue's scope, which this payload does not carry: it is
 * resolved against the trace or thread field set when the automation is saved.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationFilter(
        @JsonView({
                AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotBlank @Schema(description = "A trace field for trace queues, a thread field for thread queues, as accepted by the traces and threads filters") String field,

        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotNull Operator operator,

        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) String key,

        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) String value) {
}
