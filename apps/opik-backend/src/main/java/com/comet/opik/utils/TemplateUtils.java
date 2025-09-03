package com.comet.opik.utils;

import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Utility class for managing SQL templates and query building operations.
 * This class provides methods for creating batch SQL queries and managing
 * template placeholders efficiently.
 */
public class TemplateUtils {

    @RequiredArgsConstructor
    public static class QueryItem {
        public final int index;
        public final boolean hasNext;
    }

    /**
     * Creates a list of query item placeholders for batch operations.
     *
     * @param size the number of items in the batch
     * @return a list of QueryItem objects with index and hasNext information
     */
    public static List<QueryItem> getQueryItemPlaceHolder(int size) {

        if (size == 0) {
            return List.of();
        }

        return IntStream.range(0, size)
                .mapToObj(i -> new QueryItem(i, i < size - 1))
                .toList();
    }

    /**
     * Creates a batch SQL template using the StringTemplateManager for better
     * memory management and template reuse.
     *
     * @param sql the base SQL template string
     * @param size the number of items in the batch
     * @return a configured ST template for batch operations
     */
    public static ST getBatchSql(String sql, int size) {
        // Use the StringTemplateManager to get a cached template instance
        ST template = StringTemplateManager.getInstance().getTemplate(sql);
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(size);

        template.add("items", queryItems);

        return template;
    }

    /**
     * Creates a new ST template instance. This method is provided for backward
     * compatibility but it's recommended to use getBatchSql() for batch operations
     * or StringTemplateManager.getInstance().getTemplate() for single templates.
     *
     * @param templateString the template string to compile
     * @return a new ST instance
     * @deprecated Use StringTemplateManager.getInstance().getTemplate() instead
     */
    @Deprecated
    public static ST createTemplate(String templateString) {
        return StringTemplateManager.getInstance().getTemplate(templateString);
    }
}
