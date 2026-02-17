package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DTO for editing an existing dataset item with partial data.
 * Used in delta changes to specify which fields to update on an existing item.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetItemEdit(
        @JsonView(DatasetItemEdit.View.Write.class) @NotNull @Schema(description = "Row ID of the item to edit (from API response)", required = true) UUID id,

        @JsonView(DatasetItemEdit.View.Write.class) @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "New data to merge with existing item data") Map<String, JsonNode> data,

        @JsonView(DatasetItemEdit.View.Write.class) @Schema(description = "New tags to replace existing item tags") Set<String> tags,

        @JsonView(DatasetItemEdit.View.Write.class) @Schema(description = "New evaluators to replace existing item evaluators") List<@Valid EvaluatorItem> evaluators,

        @JsonView(DatasetItemEdit.View.Write.class) @Schema(description = "New execution policy to replace existing item execution policy") @Valid ExecutionPolicy executionPolicy) {

    public static class View {
        public static class Write {
        }
    }
}
