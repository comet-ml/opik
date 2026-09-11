package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public interface Filter {

    @JsonProperty
    Field field();

    @JsonProperty
    Operator operator();

    @JsonProperty
    String key();

    @JsonProperty
    String value();

    Filter build(String decodedValue);

    Filter buildFromCustom(String customField, FieldType type, Operator operator, String key, String value);
}
