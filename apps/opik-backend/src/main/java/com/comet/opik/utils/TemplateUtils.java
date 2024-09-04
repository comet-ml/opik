package com.comet.opik.utils;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.stream.IntStream;

public class TemplateUtils {

    @RequiredArgsConstructor
    public static class QueryItem {
        public final int index;
        public final boolean hasNext;
    }

    public static List<QueryItem> getQueryItemPlaceHolder(int size) {

        if (size == 0) {
            return List.of();
        }

        return IntStream.range(0, size)
                .mapToObj(i -> new QueryItem(i, i < size - 1))
                .toList();
    }
}
