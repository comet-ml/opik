package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.Set;

/**
 * Represents updates to apply to one or more prompt versions.
 *
 * <p><strong>Important:</strong> Prompt versions are immutable by design. Once created, their core properties
 * (template, metadata, change description) cannot be modified. This immutability ensures version history integrity
 * and reproducibility.</p>
 *
 * <p><strong>Mutable Fields:</strong></p>
 * <ul>
 *     <li>Only organizational properties (such as tags etc.) can be updated without affecting version semantics</li>
 * </ul>
 *
 * <p>Future extensions may allow updating additional organizational properties while preserving the immutability
 * of core properties.</p>
 *
 * @see PromptVersionBatchUpdate
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = """
        Update to apply to prompt versions.
        Note: Prompt versions are immutable by design.
        Only organizational properties (such as tags etc.) can be updated.
        Core properties like template, metadata etc. cannot be modified after creation.
        """)
public record PromptVersionUpdate(
        @Schema(description = """
                Tags to set or merge with existing tags. Follows PATCH semantics:
                - If merge_tags is true, these tags will be added to existing tags.
                - If merge_tags is false, these tags will replace all existing tags.
                - null: preserve existing tags (no change).
                - empty set: clear all tags merge_tags is false.
                """) Set<@NotBlank String> tags) {
}
