package com.comet.opik.utils;

import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupString;

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

    /**
     * Creates a new ephemeral StringTemplate instance with a dedicated STGroup.
     * This prevents memory leaks by ensuring templates are not cached in the default STGroup.
     * Each ST instance created by this method can be garbage collected after use.
     *
     * @param template the template string to compile
     * @return a new ST instance with its own isolated STGroup
     */
    public static ST newST(String template) {
        // Create a dedicated STGroup for this template to avoid caching in the default group
        STGroup group = new STGroupString(template);
        return new ST(group, template);
    }
}
