package com.comet.opik.api.annotationqueue;

import com.comet.opik.api.AnnotationQueue;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

import static com.comet.opik.api.AnnotationQueueAutomation.MAX_GROUPS;

/**
 * Disjunction of conjunctions: an item matches when <em>any</em> group matches, and a group matches
 * when <em>all</em> of its conditions do. Mirrors the "Add AND condition" / "Add OR group" controls.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Conditions(
        @JsonView({
                AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotEmpty @Size(max = MAX_GROUPS, message = "cannot exceed "
                        + MAX_GROUPS + " groups") @Valid List<@NotNull ConditionGroup> groups) {
}
