package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterStrategy;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.BadRequestException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public class ExperimentsComparisonFilter extends FilterImpl {

    public static final TypeReference<List<ExperimentsComparisonFilter>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final Map<String, ExperimentsComparisonField> VALID_KNOWN_FIELD_VALUES = Map.of(
            "feedback_scores", ExperimentsComparisonField.FEEDBACK_SCORES,
            "output", ExperimentsComparisonField.OUTPUT);

    //TODO: Refactor filter builder to support custom fields
    @RequiredArgsConstructor
    @Builder
    @Data
    public static class ExperimentsComparisonFieldImpl implements Field {

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

        if (VALID_KNOWN_FIELD_VALUES.containsKey(field) && type != VALID_KNOWN_FIELD_VALUES.get(field).getType()) {
            throw new BadRequestException("Invalid filters query parameter '%s'".formatted(field));
        }

        if (VALID_KNOWN_FIELD_VALUES.containsKey(field)) {
            return VALID_KNOWN_FIELD_VALUES.get(field);
        }

        return new ExperimentsComparisonFieldImpl(field, type);
    }

    private String field;
    private FieldType type;
    private Operator operator;
    private String key;
    private String value;

    @JsonCreator
    public ExperimentsComparisonFilter(
            @JsonProperty(value = "field", required = true) String field,
            @JsonProperty(value = "type") FieldType type,
            @JsonProperty(value = "operator", required = true) Operator operator,
            @JsonProperty("key") String key,
            @JsonProperty(value = "value", required = true) String value) {
        super(mapField(field, type), operator, key, value);
        this.field = field;
        this.type = type;
        this.key = key;
        this.value = value;
        this.operator = operator;
    }

    public FieldType getType() {
        return type;
    }

    @Override
    public Filter build(String decodedValue) {
        return toBuilder().value(decodedValue).build();
    }
}