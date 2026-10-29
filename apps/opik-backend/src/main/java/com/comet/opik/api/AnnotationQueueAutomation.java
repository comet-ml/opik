package com.comet.opik.api;

import com.comet.opik.api.annotationqueue.Conditions;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
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
         * Queue size at which automation stops adding: once the queue holds this many items, however they
         * got there, automation adds no more. Items added by hand are never refused.
         *
         * <p>Null means "leave the stored ceiling alone", as everywhere else on this resource. To remove
         * one, send {@code clear_max_items_in_queue}.
         */
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Positive Integer maxItemsInQueue,
        /**
         * Removes the stored ceiling. Write-only, and the only way to say so: null everywhere on this
         * resource means "leave what is stored alone", so a removal has to be stated rather than implied
         * by an absent field. Follows {@code DatasetItemUpdate.clearExecutionPolicy}.
         */
        @JsonView(AnnotationQueue.View.Write.class) @Nullable @Schema(description = "When true, removes the item ceiling so automation adds without bound") Boolean clearMaxItemsInQueue) {

    public static final int MAX_GROUPS = 5;
    public static final int MAX_CONDITIONS_PER_GROUP = 5;

    @JsonIgnore
    @AssertTrue(message = "max_items_in_queue and clear_max_items_in_queue are mutually exclusive") public boolean isCeilingEitherSetOrCleared() {
        return !Boolean.TRUE.equals(clearMaxItemsInQueue) || maxItemsInQueue == null;
    }
}
