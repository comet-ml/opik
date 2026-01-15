package com.comet.opik.domain;

import com.comet.opik.api.grouping.GroupBy;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.domain.filter.FilterQueryBuilder.JSONPATH_ROOT;

@Singleton
@Slf4j
public class GroupingQueryBuilder {

    /**
     * Regex pattern for validating JSON path keys.
     *
     * Validates JSON paths that:
     * - Start with '$'
     * - May be followed by:
     *   - Dot notation for object keys (e.g., .key, .key_name)
     *   - Array indices (e.g., [0], [123])
     *   - Bracket notation for string keys (e.g., ['key'], ['complex.key'])
     * - Allows multiple segments (e.g., $.key[0]['another_key'], $.key1.key2)
     *
     * Examples of valid paths:
     *   $, $.key, $['key'], $[0], $[4].model, $.key[0]['another_key'], $.key1.key2, $.input.key[4].role, $.input['key1'][12]['key2']
     *
     * Examples of invalid paths:
     *   $[0].['model weird'], $.key with space, $[abc], $['unterminated], model.xx, $.
     *
     */
    private static final String VALID_JSON_KEY_REGEXP = "^\\$(?:\\.(?:[A-Za-z0-9_]+)|\\[\\d+\\]|\\['(?:[^'\\\\]|\\\\.)*'\\])*$";
    public static final String DUMMY_JSON_KEY = "$.__dummy__";
    public static final String JSON_FIELD = "JSON_VALUE(%s, '%s')";

    public void addGroupingTemplateParams(@NonNull List<GroupBy> groups, @NonNull ST template) {
        List<String> groupings = groups.stream()
                .map(group -> switch (group.type()) {
                    case DICTIONARY -> JSON_FIELD.formatted(group.field(), getKeyAndValidate(group));
                    case STRING -> group.field();
                    case LIST -> "arrayJoin(%s)".formatted(group.field());
                    default -> throw new BadRequestException("Unsupported grouping field type: " + group.type());
                })
                .toList();

        String groupByClause = String.join(", ", groupings);
        String groupBySelect = IntStream.range(0, groups.size())
                .mapToObj(i -> "%s AS group_%d".formatted(groupings.get(i), i))
                .collect(Collectors.joining(", "));

        template.add("groupBy", groupByClause);
        template.add("groupSelects", groupBySelect);
    }

    private String getKeyAndValidate(GroupBy group) {

        String key = getKey(group);
        return isValidJsonPath(key) ? key : DUMMY_JSON_KEY;
    }

    private String getKey(GroupBy group) {

        if (group.key().startsWith(JSONPATH_ROOT)) {
            return group.key();
        }

        if (group.key().startsWith("[") || group.key().startsWith(".")) {
            return "%s%s".formatted(JSONPATH_ROOT, group.key());
        }

        return "%s.%s".formatted(JSONPATH_ROOT, group.key());
    }

    static boolean isValidJsonPath(String path) {
        // must start with "$" and match allowed patterns
        return path.matches(VALID_JSON_KEY_REGEXP);
    }
}
