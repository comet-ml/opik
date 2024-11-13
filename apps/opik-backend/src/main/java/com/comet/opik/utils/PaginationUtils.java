package com.comet.opik.utils;

import lombok.NonNull;

import java.util.List;

public class PaginationUtils {
    public static String ERR_PAGE_INVALID = "invalid value for page '%d'";
    public static String ERR_SIZE_INVALID = "invalid value for size '%d'";

    public static <T> List<T> paginate(int page, int size, @NonNull List<T> elements) {
        int offset = (page - 1) * size;

        if (offset >= elements.size()) {
            return List.of();
        }

        return elements.subList(offset, Math.min(offset + size, elements.size()));
    }
}
