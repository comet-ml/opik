package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Internal representation of a dataset item within a versioned dataset.
 * <p>
 * This record separates the row identifier (which changes per version snapshot)
 * from the stable item identifier that tracks the same logical item across versions.
 *
 * @param datasetItemId Stable identifier for the logical item across versions.
 *                      This ID remains the same when an item is edited across versions.
 * @param datasetId     The dataset this item belongs to.
 * @param data          The item's data as a key-value map of JSON nodes.
 * @param source        The source of the item (sdk, manual, span, trace).
 * @param traceId       Optional trace ID if the item was created from a trace.
 * @param spanId        Optional span ID if the item was created from a span.
 * @param tags          Optional set of tags associated with the item.
 * @param createdAt     When the item was originally created.
 * @param lastUpdatedAt When the item was last updated.
 * @param createdBy     Who created the item.
 * @param lastUpdatedBy Who last updated the item.
 */
@Builder(toBuilder = true)
public record VersionedDatasetItem(
        UUID datasetItemId,
        UUID datasetId,
        Map<String, JsonNode> data,
        DatasetItemSource source,
        UUID traceId,
        UUID spanId,
        Set<String> tags,
        Instant createdAt,
        Instant lastUpdatedAt,
        String createdBy,
        String lastUpdatedBy) {

    /**
     * Creates a VersionedDatasetItem from a DatasetItem API model.
     * <p>
     * When converting from the API model:
     * - If datasetItemId is provided, use it (for existing items being updated)
     * - Otherwise, the caller should generate a new datasetItemId for new items
     *
     * @param item          The API DatasetItem to convert
     * @param datasetItemId The stable item identifier (either from existing item or newly generated)
     * @param datasetId     The dataset ID
     * @return A VersionedDatasetItem ready for storage
     */
    public static VersionedDatasetItem fromDatasetItem(DatasetItem item, UUID datasetItemId, UUID datasetId) {
        return VersionedDatasetItem.builder()
                .datasetItemId(datasetItemId)
                .datasetId(datasetId)
                .data(item.data())
                .source(item.source())
                .traceId(item.traceId())
                .spanId(item.spanId())
                .tags(item.tags())
                .createdAt(item.createdAt())
                .lastUpdatedAt(item.lastUpdatedAt())
                .createdBy(item.createdBy())
                .lastUpdatedBy(item.lastUpdatedBy())
                .build();
    }

    /**
     * Converts this versioned item back to a DatasetItem API model.
     * The datasetItemId becomes the id in the API model.
     *
     * @return A DatasetItem for API responses
     */
    public DatasetItem toDatasetItem() {
        return DatasetItem.builder()
                .id(datasetItemId)
                .datasetId(datasetId)
                .data(data)
                .source(source)
                .traceId(traceId)
                .spanId(spanId)
                .tags(tags)
                .createdAt(createdAt)
                .lastUpdatedAt(lastUpdatedAt)
                .createdBy(createdBy)
                .lastUpdatedBy(lastUpdatedBy)
                .build();
    }
}
