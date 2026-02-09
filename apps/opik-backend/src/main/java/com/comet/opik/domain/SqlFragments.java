package com.comet.opik.domain;

/**
 * Shared SQL fragments for StringTemplate-based DAO queries.
 * These fragments are used across multiple DAOs to ensure consistency.
 */
public class SqlFragments {

    private SqlFragments() {
    }

    /**
     * Generates a StringTemplate fragment for handling tag updates in batch operations.
     * Supports both the new tagsToAdd/tagsToRemove API and the legacy tags + mergeTags API.
     *
     * <p>Usage example in DAO:
     * <pre>
     * private static final String BULK_UPDATE = """
     *     ...
     *     """ + SqlFragments.tagUpdateFragment("s.tags") + """
     *      as tags,
     *     ...
     *     """;
     * </pre>
     *
     * <p>Template parameters used:
     * <ul>
     *   <li>tags_to_add: Set of tags to add</li>
     *   <li>tags_to_remove: Set of tags to remove</li>
     *   <li>tags: Legacy tag set</li>
     *   <li>merge_tags: Legacy merge flag</li>
     * </ul>
     *
     * @param tagsColumnRef The table alias + column name (e.g., "s.tags", "t.tags", "src.tags", "tags")
     * @return StringTemplate fragment for tag update logic
     */
    public static String tagUpdateFragment(String tagsColumnRef) {
        return """
                <if(tags_to_add || tags_to_remove)>
                    <if(tags_to_add && tags_to_remove)>
                        arrayDistinct(arrayConcat(arrayFilter(x -> NOT has(:tags_to_remove, x), TAGS_COL), :tags_to_add))
                    <elseif(tags_to_add)>
                        arrayDistinct(arrayConcat(TAGS_COL, :tags_to_add))
                    <elseif(tags_to_remove)>
                        arrayFilter(x -> NOT has(:tags_to_remove, x), TAGS_COL)
                    <endif>
                <elseif(tags)>
                    <if(merge_tags)>arrayDistinct(arrayConcat(TAGS_COL, :tags))<else>:tags<endif>
                <else>
                    TAGS_COL
                <endif>"""
                .replace("TAGS_COL", tagsColumnRef);
    }
}
