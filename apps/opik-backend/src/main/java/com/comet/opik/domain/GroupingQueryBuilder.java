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

    public static final String JSON_FIELD = "JSON_VALUE(%s, '%s')";

    public void addGroupingTemplateParams(@NonNull List<GroupBy> groups, @NonNull ST template) {
        List<String> groupings = groups.stream()
                .map(group -> switch (group.type()) {
                    case DICTIONARY -> JSON_FIELD.formatted(group.field(), getKey(group));
                    case STRING -> group.field();
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

    private String getKey(GroupBy group) {

        if (group.key().startsWith(JSONPATH_ROOT)) {
            return group.key();
        }

        if (group.key().startsWith("[") || group.key().startsWith(".")) {
            return "%s%s".formatted(JSONPATH_ROOT, group.key());
        }

        return "%s.%s".formatted(JSONPATH_ROOT, group.key());
    }
}