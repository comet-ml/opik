package com.comet.opik.utils;

import lombok.NonNull;

import java.util.List;

public class PaginationUtils {
    public static <T> List<T> paginate(int page, int size, @NonNull List<T> elements) {
        int offset = (page - 1) * size;

        if (offset >= elements.size()) {
            return List.of();
        }

        if (size > elements.size()) {
            return elements;
        }

        return elements.subList(offset, Math.min(offset + size, elements.size()));
    }
}
