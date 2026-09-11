package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import lombok.experimental.SuperBuilder;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.api.filter.Field.INPUT_QUERY_PARAM;
import static com.comet.opik.api.filter.Field.OUTPUT_QUERY_PARAM;

@SuperBuilder(toBuilder = true)
public class SpanFilter extends FilterImpl {

    public static final TypeReference<List<SpanFilter>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final Map<FieldType, Map<String, Field>> CUSTOM_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<FieldType, Map<String, Field>>builder()
                    .put(FieldType.STRING, ImmutableMap.of(
                            INPUT_QUERY_PARAM, SpanField.INPUT,
                            OUTPUT_QUERY_PARAM, SpanField.OUTPUT))
                    .put(FieldType.DICTIONARY, ImmutableMap.of(
                            INPUT_QUERY_PARAM, SpanField.INPUT_JSON,
                            OUTPUT_QUERY_PARAM, SpanField.OUTPUT_JSON))
                    .build());

    @JsonCreator
    public SpanFilter(@JsonProperty(value = "field", required = true) SpanField field,
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
        return Optional.ofNullable(CUSTOM_FIELDS_MAP.get(type))
                .map(map -> map.get(customField))
                .map(field -> SpanFilter.builder()
                        .field(field)
                        .operator(operator)
                        .key(key)
                        .value(value)
                        .build())
                .orElse(null);
    }
}
