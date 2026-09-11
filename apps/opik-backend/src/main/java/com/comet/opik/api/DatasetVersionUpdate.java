package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetVersionUpdate(
        @Schema(description = "Optional description of changes in this version", example = "Updated baseline version") String changeDescription,
        @Valid @Size(max = 100) @Schema(description = "Optional list of tags to add to this version", example = "[\"production\", \"reviewed\"]") List<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Each tag must be at most 100 characters") String> tagsToAdd) {
}
