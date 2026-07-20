package com.comet.opik.api.sorting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("SortingFactory")
class SortingFactoryTest {

    private final SortingFactory factory = new SortingFactory() {
        @Override
        public List<String> getSortableFields() {
            return List.of("id", "name");
        }
    };

    @Test
    void newSorting__whenFieldIsNull__ignoresItWithoutThrowing() {
        // field omitted -> null; previously NPE'd via getSortableFields().contains(null) on an immutable List.
        assertThatCode(() -> factory.newSorting("[{\"direction\":\"DESC\"}]")).doesNotThrowAnyException();
        assertThat(factory.newSorting("[{\"direction\":\"DESC\"}]")).isEmpty();
    }

    @Test
    void newSorting__whenFieldIsBlank__ignoresIt() {
        assertThat(factory.newSorting("[{\"field\":\"   \",\"direction\":\"ASC\"}]")).isEmpty();
    }

    @Test
    void newSorting__whenFieldIsUnsupported__ignoresIt() {
        assertThat(factory.newSorting("[{\"field\":\"unknown\",\"direction\":\"ASC\"}]")).isEmpty();
    }

    @Test
    void newSorting__whenFieldIsSupported__returnsIt() {
        var result = factory.newSorting("[{\"field\":\"name\",\"direction\":\"DESC\"}]");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).field()).isEqualTo("name");
        assertThat(result.get(0).direction()).isEqualTo(Direction.DESC);
    }

    @Test
    void newSorting__whenAnEntryHasNullField__keepsTheValidEntry() {
        var result = factory.newSorting("[{\"field\":\"name\",\"direction\":\"ASC\"},{\"direction\":\"DESC\"}]");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).field()).isEqualTo("name");
    }

    @Test
    void newSorting__whenNullFieldOnDatasetsFactory__ignoresItWithoutThrowing() {
        // Regression: the null field reaches SortingFactoryDatasets.processFields -> ensureBindKeyParam,
        // which called field.startsWith(...) and NPE'd before the validity filter ran.
        var datasetsFactory = new SortingFactoryDatasets();

        assertThatCode(() -> datasetsFactory.newSorting("[{\"direction\":\"DESC\"}]")).doesNotThrowAnyException();
        assertThat(datasetsFactory.newSorting("[{\"direction\":\"DESC\"}]")).isEmpty();
    }
}
