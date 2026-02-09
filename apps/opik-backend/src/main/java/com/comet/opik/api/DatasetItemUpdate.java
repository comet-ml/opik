package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
        @Schema(description = "Tags") Set<String> tags,
        @Schema(description = "Tags to add") Set<String> tagsToAdd,
        @Schema(description = "Tags to remove") Set<String> tagsToRemove,
        @Schema(description = "Evaluators") List<@Valid EvaluatorItem> evaluators,
        @Schema(description = "Execution policy") @Valid ExecutionPolicy executionPolicy,
        @Schema(description = "When true, clears the item-level execution policy (falls back to dataset-level)") Boolean clearExecutionPolicy) {
}
