package com.comet.opik.api.sorting;

import java.util.List;

import static java.util.Arrays.asList;

public class SortableFields {
    public static final String NAME = "name";
    public static final String LAST_UPDATED_AT = "last_updated_at";
    public static final String CREATED_AT = "created_at";

    public static final List<String> PROJECT = asList(new String[]{
            NAME,
            LAST_UPDATED_AT,
            CREATED_AT,
    });
}
