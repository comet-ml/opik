package com.comet.opik.api;

import com.comet.opik.api.validation.HasProjectIdentifier;
import com.comet.opik.api.validation.ProjectIdentifierValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@ProjectIdentifierValidation
public record AgentConfigRemoveValues(
        @Nullable @Schema(description = "Project ID. Either project_id or project_name must be provided") UUID projectId,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "Project name. Either project_id or project_name must be provided") String projectName,
        @NotEmpty @Size(max = 250) Set<@NotBlank String> keys)
        implements
            HasProjectIdentifier {
}
