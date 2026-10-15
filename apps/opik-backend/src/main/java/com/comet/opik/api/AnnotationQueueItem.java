package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Queue membership for one item. The item's own content (trace or thread) is fetched from its own API —
 * this carries only what the queue knows and the traces API deliberately does not expose.
 *
 * <p>A row has existed in {@code annotation_queue_items} since the feature shipped, but nothing ever read
 * one individually: reads were either aggregates for the queue list or joins used as a filter. {@code
 * source} is the first field on it worth returning, which is why this type is new.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueItem(
        @JsonView({
                View.Public.class}) UUID id,
        @JsonView({View.Public.class}) AnnotationQueueItemSource source) {

    public static class View {
        public static class Public {
        }
    }

    /**
     * Result of a metadata lookup. Not a page: the caller asks for the ids it is currently displaying, so
     * there is nothing to paginate and no total to report. Ids with no queue membership are simply absent.
     */
    @Builder(toBuilder = true)
    public record AnnotationQueueItems(
            @JsonView({
                    View.Public.class}) List<AnnotationQueueItem> content) {
    }
}
