package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * Request to update multiple prompt versions in a single operation.
 *
 * <p><strong>Immutability Constraints:</strong></p>
 * <ul>
 *   <li>Prompt versions are immutable by design - their functional properties cannot be changed after creation</li>
 *   <li>Only organizational properties (such as tags etc.) can be updated</li>
 *   <li>Core properties (template, metadata, change_description etc.) remain immutable for version integrity</li>
 * </ul>
 *
 * @see PromptVersionUpdate
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = """
        Request to update one or more prompt versions.
        Note: Prompt versions are immutable by design - only organizational properties (such as tags etc.) can be updated.
        """)
public record PromptVersionBatchUpdate(
        @NotEmpty @Size(min = 1, max = 1000) @Schema(description = "IDs of prompt versions to update") Set<@NotNull UUID> ids,
        @NotNull @Valid @Schema(description = "Updates to apply to all specified prompt versions") PromptVersionUpdate update,
        @Schema(description = """
                Tag merge behavior:
                - true: Add new tags to existing tags (union)
                - false: Replace all existing tags with new tags (default behaviour if not provided)
                """, defaultValue = "false") Boolean mergeTags) {

    @Override
    public Boolean mergeTags() {
        return Boolean.TRUE.equals(mergeTags);
    }
}
