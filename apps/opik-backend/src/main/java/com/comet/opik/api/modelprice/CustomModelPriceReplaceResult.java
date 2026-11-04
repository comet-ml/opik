package com.comet.opik.api.modelprice;

import com.comet.opik.domain.cost.CustomModelPriceValidator;
import com.comet.opik.domain.cost.ModelPriceReplaceResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NonNull;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceReplaceResult(
        long version,
        @Schema(description = "False for a dry run, and when the rows sent match the stored list") boolean saved,
        @NonNull List<CustomModelPriceRow> created,
        @NonNull List<Change> updated,
        @NonNull List<CustomModelPriceRow> deleted,
        int unchanged,
        @NonNull List<CustomModelPriceValidator.Problem> warnings) {

    public record Change(@NonNull CustomModelPriceRow before, @NonNull CustomModelPriceRow after) {
    }

    public static CustomModelPriceReplaceResult from(@NonNull ModelPriceReplaceResult result) {
        var diff = result.diff();
        return new CustomModelPriceReplaceResult(
                result.version(),
                result.saved(),
                diff.created().stream().map(CustomModelPriceRow::from).toList(),
                diff.updated().stream()
                        .map(change -> new Change(CustomModelPriceRow.from(change.before()),
                                CustomModelPriceRow.from(change.after())))
                        .toList(),
                diff.deleted().stream().map(CustomModelPriceRow::from).toList(),
                diff.unchanged().size(),
                result.warnings());
    }
}
