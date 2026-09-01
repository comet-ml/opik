package com.comet.opik.api.resources.utils.datasets;

import com.comet.opik.api.DatasetItem;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class DatasetItemAssertions {

    public static final String[] IGNORED_FIELDS_DATA_ITEM = {"createdAt", "lastUpdatedAt", "experimentItems",
            "createdBy", "lastUpdatedBy", "datasetId", "tags", "datasetItemId", "runSummariesByExperiment"};

    /**
     * Extends {@link #IGNORED_FIELDS_DATA_ITEM} for call sites that ignore an extra field here and then assert it
     * separately, because it needs different semantics than plain recursive equality.
     */
    public static String[] ignoredFieldsPlus(String... extraFields) {
        return Stream.concat(Arrays.stream(IGNORED_FIELDS_DATA_ITEM), Arrays.stream(extraFields))
                .toArray(String[]::new);
    }

    public static void assertDatasetItem(DatasetItem actual, DatasetItem expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expected);
    }

    public static void assertDatasetItems(List<DatasetItem> actual, List<DatasetItem> expected) {
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expected);
    }

    public static void assertDatasetItemsInOrder(List<DatasetItem> actual, List<DatasetItem> expected) {
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .containsExactlyElementsOf(expected);
    }

    public static void assertDatasetItemsInAnyOrder(List<DatasetItem> actual, DatasetItem... expected) {
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .containsExactlyInAnyOrder(expected);
    }

    public static void assertDatasetItemsContain(List<DatasetItem> actual, List<DatasetItem> expected) {
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .containsAll(expected);
    }
}
