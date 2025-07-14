package com.comet.opik.api.sorting;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static java.util.Arrays.asList;

public class SortingFactoryPrompts extends SortingFactory {
    @Override
    public List<String> getSortableFields() {
        return asList(TAGS);
    }
}
