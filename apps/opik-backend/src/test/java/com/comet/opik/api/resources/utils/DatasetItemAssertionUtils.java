package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetType;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class DatasetItemAssertionUtils {

    public static void assertInputData(DatasetItem item, Dataset dataset, JsonNode expectedInput) {
        if (dataset.type() == DatasetType.TEST_SUITE) {
            if (expectedInput.isObject()) {
                expectedInput.fields().forEachRemaining(
                        field -> assertThat(item.data()).containsKey(field.getKey()));
            } else {
                assertThat(item.data()).containsKey("input");
                assertThat(item.data().get("input")).isEqualTo(expectedInput);
            }
        } else {
            assertThat(item.data()).containsKey("input");
            assertThat(item.data().get("input")).isEqualTo(expectedInput);
            assertThat(item.data()).containsKey("expected_output");
        }
    }

    public static void assertMapKeys(DatasetItem item, Dataset dataset, JsonNode expectedInput,
            Set<String> expectedKeys, Set<String> unexpectedKeys) {
        assertInputData(item, dataset, expectedInput);
        if (dataset.type() != DatasetType.TEST_SUITE) {
            expectedKeys.forEach(key -> assertThat(item.data()).containsKey(key));
            unexpectedKeys.forEach(key -> assertThat(item.data()).doesNotContainKey(key));
        }
    }
}
