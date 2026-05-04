package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.List;

/**
 * Bespoke fixed-tier compressor for datasets — always returns
 * {@link CompressionTier#SUMMARY}. The dataset corpus can be huge; the SUMMARY
 * tier delivers metadata + a 5-item sample so the agent can reason about the
 * shape, then drill into specific items via {@code read(type=dataset_item, ...)}.
 *
 * <p>String fields inside each sample item's {@code data} are truncated to
 * {@link #DATA_FIELD_TRUNCATION_LENGTH} chars; the dataset {@code description}
 * is truncated to {@link #DESCRIPTION_TRUNCATION_LENGTH}.
 *
 * <p>The {@code tier} arg is ignored — fixed-tier compressor.
 *
 * <p>Returned {@link CompressionResult#payload()} is the bare inner content;
 * {@code ReadTool} wraps it in the {@code data} field of the response envelope.
 */
@Singleton
public final class DatasetCompressor implements EntityCompressor {

    static final int SAMPLE_SIZE = 5;
    static final int DATA_FIELD_TRUNCATION_LENGTH = 200;
    static final int DESCRIPTION_TRUNCATION_LENGTH = 500;
    static final String DRILL_DOWN_HINT = "Use read(type=dataset_item, id=<id>, tier=FULL) to fetch a"
            + " specific item, or jq(type=dataset, id=<dataset_id>, expression='.sample_items[].id') to"
            + " list sample item ids already in context.";

    @Override
    public EntityType type() {
        return EntityType.DATASET;
    }

    /**
     * Builds the FULL composite JSON {@code {"dataset": ..., "sample_items": [...]}}. Exposed
     * so {@code ReadTool} can cache the un-truncated form before invoking compression.
     */
    public JsonNode buildFullJson(@NonNull Dataset dataset, @NonNull List<DatasetItem> sampleItems) {
        var mapper = JsonUtils.getMapper();
        ObjectNode node = mapper.createObjectNode();
        node.set("dataset", mapper.valueToTree(dataset));
        node.set("sample_items", mapper.valueToTree(sampleItems));
        return node;
    }

    public CompressionResult compress(@NonNull Dataset dataset, @NonNull List<DatasetItem> sampleItems) {
        var mapper = JsonUtils.getMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("name", dataset.name());
        node.put("description", StringTruncator.truncate(dataset.description(), DESCRIPTION_TRUNCATION_LENGTH, null));
        node.put("visibility", dataset.visibility() != null ? dataset.visibility().toString() : null);
        node.put("created_at", dataset.createdAt() != null ? dataset.createdAt().toString() : null);
        node.put("total_item_count", dataset.datasetItemsCount());

        ArrayNode samples = mapper.createArrayNode();
        for (var item : sampleItems.stream().limit(SAMPLE_SIZE).toList()) {
            ObjectNode sample = mapper.createObjectNode();
            sample.put("id", item.id() != null ? item.id().toString() : null);
            sample.set("data", buildTruncatedDataNode(item));
            samples.add(sample);
        }
        node.set("sample_items", samples);
        node.put("drill_down_hint", DRILL_DOWN_HINT);
        return CompressionResult.builder()
                .payload(node)
                .tier(CompressionTier.SUMMARY)
                .build();
    }

    private static JsonNode buildTruncatedDataNode(DatasetItem item) {
        var mapper = JsonUtils.getMapper();
        ObjectNode data = mapper.createObjectNode();
        if (item.data() == null) {
            return data;
        }
        item.data().forEach((key, value) -> {
            if (value == null || value.isNull()) {
                data.set(key, mapper.nullNode());
                return;
            }
            if (value.isTextual()) {
                data.put(key, StringTruncator.truncate(value.asText(), DATA_FIELD_TRUNCATION_LENGTH, null));
            } else {
                data.set(key, PathAwareTruncator.truncate(value, DATA_FIELD_TRUNCATION_LENGTH));
            }
        });
        return data;
    }

}