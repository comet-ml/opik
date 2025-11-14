package com.comet.opik.api;

import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpanSearchStreamRequest(
        UUID traceId,
        String projectName,
        UUID projectId,
        SpanType type,
        List<SpanFilter> filters,
        @Schema(description = "Max number of spans to be streamed", defaultValue = "500") @Min(1) @Max(2000) Integer limit,
        UUID lastRetrievedId,
        @Schema(description = "Truncate image included in either input, output or metadata", defaultValue = "true") @DefaultValue("true") boolean truncate,
        @Schema(description = "Filter spans created from this time (ISO-8601 format).") Instant fromTime,
        @Schema(description = "Filter spans created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'.") Instant toTime) {

    @Override
    public Integer limit() {
        return limit == null ? 500 : limit;
    }
}
