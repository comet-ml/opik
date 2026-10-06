package com.comet.opik.api.annotationqueue;

import com.comet.opik.api.AnnotationQueue;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * A single threshold on a named feedback score. The name is deliberately not validated against the
 * workspace's feedback definitions — configuring an automation before the score exists is a legitimate
 * order of operations.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScoreCondition(
        @JsonView({
                AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotBlank @Size(max = 255) String scoreName,

        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotNull ScoreConditionOperator operator,

        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotNull Double value) {
}
