package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PromptVersionRetrieve(
        @NotBlank String name,
        String commit,
        @Pattern(regexp = Environment.NAME_PATTERN, message = Environment.NAME_PATTERN_MESSAGE) @Size(max = 150, message = "cannot exceed 150 characters") @Schema(description = "If provided, resolves to the version mapped to this environment for the prompt; mutually exclusive with commit and version_number") String environment,
        @Pattern(regexp = "v\\d+", message = "must match v<N>") @Size(max = 10, message = "cannot exceed 10 characters") @Schema(description = "If provided, resolves to the version with this sequential number (e.g. v3); mutually exclusive with commit and environment") String versionNumber,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If provided, scopes the search to the specified project") String projectName) {

    @JsonIgnore
    @AssertTrue(message = "commit, environment and version_number are mutually exclusive") public boolean isResolutionMutuallyExclusive() {
        long provided = (StringUtils.isNotBlank(commit) ? 1 : 0)
                + (StringUtils.isNotBlank(environment) ? 1 : 0)
                + (StringUtils.isNotBlank(versionNumber) ? 1 : 0);
        return provided <= 1;
    }
}
