package com.comet.opik.api;

import com.comet.opik.api.validation.DatasetItemBatchValidation;
import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@DatasetItemBatchValidation
public record DatasetItemBatch(
        @JsonView( {
                DatasetItem.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, dataset_id must be provided") String datasetName,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "If null, dataset_name must be provided") UUID datasetId,
        @JsonView({DatasetItem.View.Write.class}) @NotNull @Size(min = 1, max = 1000) @Valid List<DatasetItem> items,
        @JsonView({
                DatasetItem.View.Write.class}) @Schema(description = "Optional batch group ID to group multiple batches into a single dataset version. If null, mutates the latest version instead of creating a new one.") UUID batchGroupId)
        implements
            RateEventContainer{

    @Override
    public long eventCount() {
        return items.size();
    }
}
