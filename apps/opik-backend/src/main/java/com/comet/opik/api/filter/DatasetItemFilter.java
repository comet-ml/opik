package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterStrategy;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Set;

@SuperBuilder(toBuilder = true)
@Getter
public class DatasetItemFilter extends FilterImpl {

    public static final TypeReference<List<DatasetItemFilter>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    @RequiredArgsConstructor
    @Builder
    @Data
    public static class DatasetItemDynamicField implements Field {

        private static final Set<FieldType> DYNAMIC_FIELD_TYPES = Set.of(FieldType.STRING, FieldType.NUMBER,
                FieldType.DICTIONARY, FieldType.LIST);

        public static boolean validType(FieldType type) {
            return DYNAMIC_FIELD_TYPES.contains(type);
        }

        private final String queryParamField;
        private final FieldType type;

        @Override
        public String getQueryParamField() {
            return queryParamField;
        }

        @Override
        public boolean isDynamic(FilterStrategy filterStrategy) {
            return filterStrategy == FilterStrategy.DATASET_ITEM;
        }
    }

    static Field mapField(String field, FieldType type) {
        return DatasetItemDynamicField.builder()
                .queryParamField(field)
                .type(type)
                .build();
    }

    @JsonCreator
    public DatasetItemFilter(@JsonProperty(value = "field", required = true) DatasetItemField field,
            @JsonProperty(value = "operator", required = true) Operator operator,
            @JsonProperty("key") String key,
            @JsonProperty(value = "value", required = true) String value) {
        super(field, operator, key, value);
    }

    @Override
    public Filter build(String decodedValue) {
        return toBuilder().value(decodedValue).build();
    }

    @Override
    public Filter buildFromCustom(String customField, FieldType type, Operator operator, String key, String value) {
        if (!DatasetItemDynamicField.validType(type)) {
            return null;
        }
        return toBuilder()
                .field(mapField(customField, type))
                .operator(operator)
                .key(key)
                .value(value)
                .build();
    }
}
