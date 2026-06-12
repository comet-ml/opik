package com.comet.opik.api.spend;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpendMetricRequest(
        UUID projectId,
        String projectName,
        @NotNull Instant intervalStart,
        @NotNull Instant intervalEnd,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String userId,
        @JsonIgnore UUID resolvedProjectId) {

    @AssertTrue(message = "interval_start must be before interval_end") public boolean isStartBeforeEnd() {
        return intervalStart == null || intervalEnd == null || intervalStart.isBefore(intervalEnd);
    }

    @AssertTrue(message = "must provide either project_id or project_name") public boolean isProjectProvided() {
        return projectId != null || StringUtils.isNotBlank(projectName);
    }
}
