package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.experimental.SuperBuilder;

import java.util.List;

@SuperBuilder(toBuilder = true)
public class ExperimentsComparisonFilter extends FilterImpl {

    public static final TypeReference<List<ExperimentsComparisonFilter>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    @JsonCreator
    public ExperimentsComparisonFilter(@JsonProperty(value = "field", required = true) ExperimentsComparisonField field,
            @JsonProperty(value = "operator", required = true) Operator operator,
            @JsonProperty("key") String key,
            @JsonProperty(value = "value", required = true) String value) {
        super(field, operator, key, value);
    }

    @Override
    public Filter build(String decodedValue) {
        return toBuilder().value(decodedValue).build();
    }
}