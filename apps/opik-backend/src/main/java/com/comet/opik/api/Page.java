package com.comet.opik.api;

import java.util.List;

public interface Page<T> {

    int size();
    int page();
    List<T> content();
    long total();
    default List<String> sortableBy() {
        return List.of();
    }
}
