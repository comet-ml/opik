package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Dataset item update request")
public record DatasetItemUpdate(
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "Dataset item input") String input,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "Dataset item expected output") String expectedOutput,
        @Schema(description = "Dataset item metadata", implementation = JsonNode.class, ref = "JsonNode") Map<String, JsonNode> metadata,
        @Schema(description = "Dataset item data", implementation = JsonNode.class, ref = "JsonNode") Map<String, JsonNode> data,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "Dataset item description") String description,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags") @Schema(description = "Tags") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tags,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to add") @Schema(description = "Tags to add") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToAdd,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags to remove") @Schema(description = "Tags to remove") Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tagsToRemove,
        @Schema(description = "Evaluators") List<@Valid EvaluatorItem> evaluators,
        @Schema(description = "Execution policy") @Valid ExecutionPolicy executionPolicy,
        @Schema(description = "When true, clears the item-level execution policy (falls back to dataset-level)") Boolean clearExecutionPolicy) {
}
