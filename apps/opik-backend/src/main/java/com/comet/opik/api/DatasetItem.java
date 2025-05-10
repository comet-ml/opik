package com.comet.opik.api;

import com.comet.opik.api.validate.SourceValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@SourceValidation
public record DatasetItem(
        @JsonView( {
                DatasetItem.View.Public.class, DatasetItem.View.Write.class}) UUID id,
        @JsonView({DatasetItem.View.Public.class, DatasetItem.View.Write.class}) UUID traceId,
        @JsonView({DatasetItem.View.Public.class, DatasetItem.View.Write.class}) UUID spanId,
        @JsonView({DatasetItem.View.Public.class, DatasetItem.View.Write.class}) @NotNull DatasetItemSource source,
        @JsonView({DatasetItem.View.Public.class,
                DatasetItem.View.Write.class}) @Schema(implementation = JsonNode.class, ref = "JsonNode") @NotEmpty Map<String, JsonNode> data,
        @JsonView({
                DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<ExperimentItem> experimentItems,
        @JsonView({DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                DatasetItem.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    @Builder(toBuilder = true)
    public record DatasetItemPage(
            @JsonView( {
                    DatasetItem.View.Public.class}) List<DatasetItem> content,
            @JsonView({DatasetItem.View.Public.class}) int page,
            @JsonView({DatasetItem.View.Public.class}) int size,
            @JsonView({DatasetItem.View.Public.class}) long total,
            @JsonView({DatasetItem.View.Public.class}) Set<Column> columns) implements Page<DatasetItem>{

        public static DatasetItemPage empty(int page) {
            return new DatasetItemPage(List.of(), page, 0, 0, Set.of());
        }
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }

}
