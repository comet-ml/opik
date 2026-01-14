package com.comet.opik.api.grouping;

import com.comet.opik.api.filter.FieldType;
import jakarta.ws.rs.BadRequestException;

import java.util.List;

public class ExperimentGroupingFactory extends GroupingFactory {

    private static final List<String> SUPPORTED_FIELDS = List.of(METADATA, DATASET_ID, TAGS);

    @Override
    public List<String> getSupportedFields() {
        return SUPPORTED_FIELDS;
    }

    @Override
    protected void validateFieldType(GroupBy group) {
        String formattedErrorMessage = ERR_INVALID_FIELD_TYPE_TEMPLATE.formatted(group.type(), group.field());
        switch (group.field()) {
            case DATASET_ID :
                // dataset_id is a FixedString(36), only STRING type is valid
                if (group.type() != FieldType.STRING) {
                    throw new BadRequestException(formattedErrorMessage);
                }
                break;
            case METADATA :
                // metadata is a String (JSON), only DICTIONARY type is valid
                if (group.type() != FieldType.DICTIONARY) {
                    throw new BadRequestException(formattedErrorMessage);
                }
                break;
            case TAGS :
                // tags is Array(String), only LIST type is valid
                if (group.type() != FieldType.LIST) {
                    throw new BadRequestException(formattedErrorMessage);
                }
                break;
            default :
                throw new BadRequestException(formattedErrorMessage);
        }
    }
}
