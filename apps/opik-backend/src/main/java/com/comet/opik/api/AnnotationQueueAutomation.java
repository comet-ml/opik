package com.comet.opik.api;

import com.comet.opik.api.annotationqueue.Conditions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

/**
 * Rules for automatically populating an annotation queue from feedback scores. Rides on the annotation
 * queue payload rather than being its own resource — automation is a property of a queue.
 *
 * <p>Score conditions are source-agnostic on purpose: a score written by an online-evaluation rule, a
 * human annotator or the SDK is treated identically, because they all land in the same analytics tables.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueAutomation(
        /**
         * Primitive, so the type carries the non-nullability rather than an annotation. Note the
         * consequence at the API edge: a payload that omits the field is read as disabled rather than
         * rejected, which is why an automation is only ever created alongside its queue.
         */
        @JsonView({
                AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) boolean enabled,

        /**
         * Nullable so flipping the toggle off is a one-field request: {@code {"enabled": false}} keeps the
         * stored conditions, which also makes enable/disable idempotent and stops two clients racing on
         * the toggle from clobbering each other's conditions. "An enabled automation needs at least one
         * group" is a cross-field rule and lives in the service.
         */
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Valid Conditions conditions,

        /**
         * Ceiling on how large automation is allowed to grow the queue: once the queue holds this many
         * items, automation stops adding. Absent means no ceiling. Nullable for the same reason as
         * conditions — a toggle-only request must not silently drop it.
         */
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Positive Integer maxItemsInQueue) {

    public static final int MAX_GROUPS = 5;
    public static final int MAX_CONDITIONS_PER_GROUP = 5;

}
