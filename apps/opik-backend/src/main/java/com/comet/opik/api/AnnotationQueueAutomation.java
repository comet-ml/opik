package com.comet.opik.api;

import com.comet.opik.api.annotationqueue.AutomationFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/**
 * Rules for automatically populating an annotation queue. Rides on the annotation queue payload rather than
 * being its own resource — automation is a property of a queue.
 *
 * <p>An item is added when it matches every filter of any one group in {@code filter_groups}: the filters are
 * the ones the traces and threads pages apply, AND-ed within a group, and the groups are OR-ed.
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
         * stored groups, which also makes enable/disable idempotent and stops two clients racing on the
         * toggle from clobbering each other's filters. "An enabled automation needs at least one group" is a
         * cross-field rule and lives in the service.
         */
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Size(max = MAX_FILTER_GROUPS, message = "cannot exceed "
                        + MAX_FILTER_GROUPS
                        + " filter groups") @Schema(description = "Items matching every filter of any one group are added. Filters are those of the traces and threads endpoints") List<@NotNull @NotEmpty @Size(max = MAX_FILTERS_PER_GROUP, message = "cannot exceed "
                                + MAX_FILTERS_PER_GROUP
                                + " filters per group") List<@NotNull @Valid AutomationFilter>> filterGroups,

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
        @JsonView(AnnotationQueue.View.Write.class) @Nullable @Schema(description = "When true, removes the item ceiling so automation adds without bound") Boolean clearMaxItemsInQueue,

        /**
         * The score conditions {@code filter_groups} replaced. Read only to reject them: unknown fields are
         * otherwise ignored, and a client still sending conditions would get an automation that silently
         * keeps its old rules or has none.
         */
        @JsonView(AnnotationQueue.View.Write.class) @Nullable @Schema(hidden = true) JsonNode conditions) {

    public static final int MAX_FILTER_GROUPS = 5;
    public static final int MAX_FILTERS_PER_GROUP = 50;

    @JsonIgnore
    @AssertTrue(message = "max_items_in_queue and clear_max_items_in_queue are mutually exclusive") public boolean isCeilingEitherSetOrCleared() {
        return !Boolean.TRUE.equals(clearMaxItemsInQueue) || maxItemsInQueue == null;
    }

    @JsonIgnore
    @AssertTrue(message = "conditions is no longer supported, use filter_groups with feedback_scores filters instead") public boolean isConditionsAbsent() {
        // A JSON null reaches a JsonNode field as NullNode, and sending null is not sending conditions.
        return conditions == null || conditions.isNull();
    }
}
