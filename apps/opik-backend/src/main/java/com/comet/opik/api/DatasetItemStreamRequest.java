package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetItemStreamRequest(
        @NotBlank String datasetName,
        UUID lastRetrievedId,
        @Min(1) @Max(2000) Integer steamLimit,
        String datasetVersion) {

    private static final int DEFAULT_STREAM_LIMIT = 2000;

    /**
     * Returns the steam limit, using 2000 as default if not provided by the client.
     */
    @Override
    public Integer steamLimit() {
        return steamLimit == null ? DEFAULT_STREAM_LIMIT : steamLimit;
    }
}
