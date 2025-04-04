package com.comet.opik.utils;

import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;

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

    public static ST getBatchSql(String sql, int size) {
        var template = new ST(sql);
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(size);

        template.add("items", queryItems);

        return template;
    }
}
