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

import java.time.Instant;
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
        @Schema(description = "Truncate input, output and metadata to slim payloads", defaultValue = "true") @DefaultValue("true") boolean truncate,
        @Schema(description = "If true, returns attachment references like [file.png]; if false, downloads and reinjects stripped attachments", defaultValue = "false") @DefaultValue("false") boolean stripAttachments,
        @Schema(description = "Filter trace threads created from this time (ISO-8601 format).") Instant fromTime,
        @Schema(description = "Filter trace threads created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'.") Instant toTime) {

    public Integer limit() {
        return limit == null ? 500 : limit;
    }
}
