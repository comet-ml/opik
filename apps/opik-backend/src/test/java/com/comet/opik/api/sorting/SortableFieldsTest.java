package com.comet.opik.api.sorting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SortableFieldsTest {

    @Test
    @DisplayName("sortsByWideTextColumn detects base and dynamic-path wide text fields (input/output/metadata)")
    void sortsByWideTextColumn_detectsWideFields() {
        // Null / empty
        assertThat(SortableFields.sortsByWideTextColumn(null)).isFalse();
        assertThat(SortableFields.sortsByWideTextColumn(List.of())).isFalse();

        // Base wide fields
        assertThat(SortableFields.sortsByWideTextColumn(field(SortableFields.INPUT))).isTrue();
        assertThat(SortableFields.sortsByWideTextColumn(field(SortableFields.OUTPUT))).isTrue();
        assertThat(SortableFields.sortsByWideTextColumn(field(SortableFields.METADATA))).isTrue();

        // Dynamic JSON paths on a wide field — exercises baseField() resolution
        assertThat(SortableFields.sortsByWideTextColumn(field("input.order"))).isTrue();
        assertThat(SortableFields.sortsByWideTextColumn(field("output.model[0].year"))).isTrue();
        assertThat(SortableFields.sortsByWideTextColumn(field("metadata.a.b.c"))).isTrue();

        // Non-wide fields (including a null field value) must not match
        assertThat(SortableFields.sortsByWideTextColumn(field(SortableFields.NAME))).isFalse();
        assertThat(SortableFields.sortsByWideTextColumn(field(SortableFields.DURATION))).isFalse();
        assertThat(SortableFields.sortsByWideTextColumn(field(null))).isFalse();
    }

    private static List<SortingField> field(String name) {
        return List.of(SortingField.builder().field(name).build());
    }
}
