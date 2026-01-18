package com.comet.opik.api.grouping;

import com.comet.opik.api.filter.FieldType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.comet.opik.api.filter.FieldType.DICTIONARY;
import static com.comet.opik.api.filter.FieldType.LIST;
import static com.comet.opik.api.filter.FieldType.STRING;

@Slf4j
public abstract class GroupingFactory {

    public static final String ERR_INVALID_GROUPING_PARAM_TEMPLATE = "Invalid grouping query parameter '%s'";
    public static final String ERR_MISSING_GROUPING_PARAM = "Required query parameter 'groups' is missing";
    public static final String ERR_EMPTY_GROUPING = "At least one grouping field must be specified";
    public static final String ERR_INVALID_GROUPING_FIELD_TEMPLATE = "Invalid grouping field: %s. Supported fields are: %s";
    public static final String ERR_MISSING_KEY = "Key must be specified for json field '%s'";
    public static final String ERR_INVALID_FIELD_TYPE_TEMPLATE = "Invalid field type '%s' for field '%s'";

    public static final String METADATA = "metadata";
    public static final String DATASET_ID = "dataset_id";
    public static final String PROJECT_ID = "project_id";
    public static final String TAGS = "tags";

    private static final TypeReference<List<GroupBy>> GROUP_BY_LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    public abstract List<String> getSupportedFields();

    protected abstract void validateFieldType(GroupBy group);

    public List<GroupBy> newGrouping(String queryParam) {
        if (StringUtils.isBlank(queryParam)) {
            throw new BadRequestException(ERR_MISSING_GROUPING_PARAM);
        }

        List<GroupBy> groups;
        try {
            groups = JsonUtils.readValue(queryParam, GROUP_BY_LIST_TYPE_REFERENCE);
        } catch (UncheckedIOException exception) {
            throw new BadRequestException(ERR_INVALID_GROUPING_PARAM_TEMPLATE.formatted(queryParam), exception);
        }

        if (CollectionUtils.isEmpty(groups)) {
            throw new BadRequestException(ERR_EMPTY_GROUPING);
        }

        // Enrich groups with types if not provided
        var enrichedGroups = groups.stream()
                .map(this::enrichGroupWithType)
                .collect(Collectors.toList());

        validateGroups(enrichedGroups);

        return enrichedGroups;
    }

    private GroupBy enrichGroupWithType(GroupBy group) {
        // If type is already set, return as is
        if (group.type() != null) {
            return group;
        }

        // Determine type based on field name
        var fieldType = getFieldType(group.field());
        return group.toBuilder().type(fieldType).build();
    }

    private FieldType getFieldType(String field) {
        // metadata is always DICTIONARY
        if (METADATA.equals(field)) {
            return DICTIONARY;
        }
        // dataset_id and project_id are STRING
        if (DATASET_ID.equals(field) || PROJECT_ID.equals(field)) {
            return STRING;
        }
        // tags is LIST
        if (TAGS.equals(field)) {
            return LIST;
        }

        // Default to STRING for unknown fields
        return STRING;
    }

    private void validateGroups(@NonNull List<GroupBy> groups) {
        for (GroupBy group : groups) {
            validateGroup(group);
        }
    }

    private void validateGroup(@NonNull GroupBy group) {
        // Validate field
        if (!getSupportedFields().contains(group.field())) {
            throw new BadRequestException(
                    ERR_INVALID_GROUPING_FIELD_TEMPLATE.formatted(group.field(), getSupportedFields().toString()));
        }

        // Check that json field contains a valid key
        if (DICTIONARY.equals(group.type()) && StringUtils.isBlank(group.key())) {
            throw new BadRequestException(ERR_MISSING_KEY.formatted(group.field()));
        }

        // Validate field type compatibility
        validateFieldType(group);
    }
}