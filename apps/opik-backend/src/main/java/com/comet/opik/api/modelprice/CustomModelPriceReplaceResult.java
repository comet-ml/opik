package com.comet.opik.api.modelprice;

import com.comet.opik.domain.cost.CustomModelPriceValidator;
import com.comet.opik.domain.cost.ModelPriceReplaceResult;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceReplaceResult(
        long version,
        @Schema(description = "False for a dry run, and when the rows sent match the stored list") boolean saved,
        @NonNull List<CustomModelPriceRow> created,
        @NonNull List<Change> updated,
        @NonNull List<CustomModelPriceRow> deleted,
        int unchanged,
        @NonNull List<CustomModelPriceValidator.Problem> warnings) {

    @Builder(toBuilder = true)
    public record Change(@NonNull CustomModelPriceRow before, @NonNull CustomModelPriceRow after) {
    }

    public static CustomModelPriceReplaceResult from(@NonNull ModelPriceReplaceResult result) {
        var diff = result.diff();
        return CustomModelPriceReplaceResult.builder()
                .version(result.version())
                .saved(result.saved())
                .created(diff.created().stream().map(CustomModelPriceRow::from).toList())
                .updated(diff.updated().stream()
                        .map(change -> Change.builder()
                                .before(CustomModelPriceRow.from(change.before()))
                                .after(CustomModelPriceRow.from(change.after()))
                                .build())
                        .toList())
                .deleted(diff.deleted().stream().map(CustomModelPriceRow::from).toList())
                .unchanged(diff.unchanged().size())
                .warnings(result.warnings())
                .build();
    }
}
