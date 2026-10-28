package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiLabelEndpoint implements HasValue {
    DECIDE("decide"),
    GENERATE("generate");

    @JsonValue
    private final String value;
}
