package com.comet.opik.api;

import com.comet.opik.api.filter.TraceThreadFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceThreadSearchStreamRequest(
        String projectName,
        UUID projectId,
        List<TraceThreadFilter> filters,
        UUID lastRetrievedThreadModelId,
        @Schema(description = "Max number of trace thread to be streamed", defaultValue = "500") @Min(1) @Max(2000) Integer limit,
        @Schema(description = "Truncate image included in either input, output or metadata", defaultValue = "true") @DefaultValue("true") boolean truncate) {

    public Integer limit() {
        return limit == null ? 500 : limit;
    }
}
