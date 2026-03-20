package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.DefaultValue;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentItemStreamRequest(
        @NotBlank String experimentName,
        @Min(1) @Max(2000) Integer limit,
        UUID lastRetrievedId,
        @Schema(description = "Truncate image included in either input, output or metadata", defaultValue = "true") @DefaultValue("true") boolean truncate,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String projectName) {

    @Override
    public Integer limit() {
        return limit == null ? 500 : limit;
    }
}
