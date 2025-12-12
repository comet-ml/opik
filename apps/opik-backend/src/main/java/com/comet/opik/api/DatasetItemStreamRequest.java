package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.DefaultValue;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetItemStreamRequest(
        @NotBlank String datasetName,
        UUID lastRetrievedId,
        @Min(1) @Max(2000) @DefaultValue("500") Integer steamLimit,
        String version) {

    @Override
    public Integer steamLimit() {
        return steamLimit == null ? 500 : steamLimit;
    }
}
