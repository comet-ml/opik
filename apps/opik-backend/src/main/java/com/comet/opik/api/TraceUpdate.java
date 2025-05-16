package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceUpdate(
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null and project_id not specified, Default Project is assumed") String projectName,
        @Schema(description = "If null and project_name not specified, Default Project is assumed") UUID projectId,
        String name,
        Instant endTime,
        @Schema(implementation = JsonListString.class) JsonNode input,
        @Schema(implementation = JsonListString.class) JsonNode output,
        JsonNode metadata,
        Set<String> tags,
        ErrorInfo errorInfo,
        String threadId) {
}