package com.comet.opik.utils;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

public class TemplateUtils {

    public static class QueryItem {
        public final int index;
        public final boolean hasNext;

        public QueryItem(int index, boolean hasNext) {
            this.index = index;
            this.hasNext = hasNext;
        }
    }

    public static List<QueryItem> getQueryItemPlaceHolder(Collection<?> items) {

        if (items == null || items.isEmpty()) {
            return List.of();
        }

        return IntStream.range(0, items.size())
                .mapToObj(i -> new QueryItem(i, i < items.size() - 1))
                .toList();
    }
}
