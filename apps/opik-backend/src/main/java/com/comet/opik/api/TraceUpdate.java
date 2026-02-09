package com.comet.opik.api;

import com.comet.opik.api.validation.InRange;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
        @InRange Instant endTime,
        @Schema(implementation = JsonListString.class) JsonNode input,
        @Schema(implementation = JsonListString.class) JsonNode output,
        @Schema(implementation = JsonListString.class) JsonNode metadata,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags") @Schema(description = "Tags") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tags,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to add") @Schema(description = "Tags to add") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToAdd,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to remove") @Schema(description = "Tags to remove") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToRemove,
        ErrorInfo errorInfo,
        String threadId,
        @PositiveOrZero Double ttft) {
}
