package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Visibility;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetCompressorTest {

    private final DatasetCompressor compressor = new DatasetCompressor();

    @Test
    void typeIsDataset() {
        assertThat(compressor.type()).isEqualTo(EntityType.DATASET);
    }

    @Test
    void alwaysReturnsSummaryTier() {
        var result = compressor.compress(dataset("d"), List.of());

        assertThat(result.tier()).isEqualTo(CompressionTier.SUMMARY);
    }

    @Test
    void includesIdentityAndDrillDownHint() {
        var ds = dataset("my-dataset");

        var result = compressor.compress(ds, List.of());

        var node = result.payload();
        assertThat(node.get("name").asText()).isEqualTo("my-dataset");
        assertThat(node.get("drill_down_hint").asText()).contains("read(type=dataset_item");
        assertThat(node.has("sample_items")).isTrue();
        assertThat(node.get("sample_items").size()).isZero();
    }

    @Test
    void capsSampleItemsAtFive() {
        var ds = dataset("d");
        var items = IntStream.range(0, 12)
                .mapToObj(i -> item("k", "small"))
                .toList();

        var result = compressor.compress(ds, items);

        assertThat(result.payload().get("sample_items").size()).isEqualTo(DatasetCompressor.SAMPLE_SIZE);
    }

    @Test
    void truncatesLongDataValues() {
        var ds = dataset("d");
        var longValue = "y".repeat(500);
        var items = List.of(item("question", longValue));

        var result = compressor.compress(ds, items);

        var sample = result.payload().get("sample_items").get(0);
        var truncated = sample.get("data").get("question").asText();
        assertThat(truncated).hasSizeLessThan(longValue.length());
        assertThat(truncated).contains("[TRUNCATED");
    }

    @Test
    void truncatesLongDescription() {
        var longDesc = "d".repeat(1_000);
        var ds = Dataset.builder()
                .id(UUID.randomUUID())
                .name("d")
                .description(longDesc)
                .visibility(Visibility.PRIVATE)
                .createdAt(Instant.now())
                .build();

        var result = compressor.compress(ds, List.of());

        var description = result.payload().get("description").asText();
        assertThat(description).hasSize(DatasetCompressor.DESCRIPTION_TRUNCATION_LENGTH
                + "[TRUNCATED 500 chars]".length());
        assertThat(description).contains("[TRUNCATED 500 chars]");
    }

    @Test
    void buildFullJsonContainsDatasetAndSamples() {
        var ds = dataset("d");
        var items = List.of(item("k", "v"));

        var full = compressor.buildFullJson(ds, items);

        assertThat(full.has("dataset")).isTrue();
        assertThat(full.has("sample_items")).isTrue();
    }

    private static Dataset dataset(String name) {
        return Dataset.builder()
                .id(UUID.randomUUID())
                .name(name)
                .visibility(Visibility.PRIVATE)
                .createdAt(Instant.now())
                .datasetItemsCount(0L)
                .build();
    }

    private static DatasetItem item(String key, String value) {
        return DatasetItem.builder()
                .id(UUID.randomUUID())
                .source(DatasetItemSource.MANUAL)
                .data(Map.of(key, JsonUtils.getMapper().valueToTree(value)))
                .build();
    }
}