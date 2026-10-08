package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Instant;

/**
 * The dashboard date range a saved widget query runs over. The query itself is read from the saved widget; the
 * caller never sends SQL.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardWidgetQueryRequest(
        @Nullable @Schema(description = "Start of the date range, bound to {{window_start}} in the saved query. Omit for all time.") Instant intervalStart,
        @Nullable @Schema(description = "End of the date range, bound to {{window_end}} in the saved query. Omit for now.") Instant intervalEnd) {
}
