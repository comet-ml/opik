package com.comet.opik.api.modelprice;

import com.comet.opik.domain.cost.CustomModelPrice;
import com.comet.opik.domain.cost.ModelPriceRow;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "One of the organization's price rows, in the shape the replace endpoint accepts back")
public record CustomModelPriceRow(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        String model,
        @Schema(description = "Empty means any provider") String provider,
        @Schema(allowableValues = {
                "fixed", "alias"}) String kind,
        @Schema(description = "USD per 1M input tokens; absent means the built-in rate", example = "1.25") String inputPrice,
        @Schema(description = "USD per 1M output tokens; absent means the built-in rate", example = "10") String outputPrice,
        @Schema(description = "USD per 1M cached input tokens read; absent means the built-in rate") String cacheReadPrice,
        @Schema(description = "USD per 1M input tokens written to the cache; absent means the built-in rate") String cacheWritePrice,
        AliasOf aliasOf,
        String note,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AliasOf(String model, @Schema(description = "Empty means the span's own provider") String provider) {
    }

    public static CustomModelPriceRow from(@NonNull ModelPriceRow row) {
        return from(row.toPrice()).toBuilder()
                .id(row.id())
                .note(row.note())
                .createdAt(row.createdAt())
                .createdBy(row.createdBy())
                .lastUpdatedAt(row.lastUpdatedAt())
                .lastUpdatedBy(row.lastUpdatedBy())
                .build();
    }

    public static CustomModelPriceRow from(@NonNull CustomModelPrice price) {
        return CustomModelPriceRow.builder()
                .model(price.modelName())
                .provider(price.provider())
                .kind(price.kind().name().toLowerCase(Locale.ROOT))
                .inputPrice(plain(price.inputPerMillion()))
                .outputPrice(plain(price.outputPerMillion()))
                .cacheReadPrice(plain(price.cacheReadPerMillion()))
                .cacheWritePrice(plain(price.cacheWritePerMillion()))
                .aliasOf(price.kind() == CustomModelPrice.Kind.ALIAS
                        ? new AliasOf(price.aliasModelName(), price.aliasProvider())
                        : null)
                .build();
    }

    // Strings, not JSON numbers: a DECIMAL(20,10) price can carry more digits than a JavaScript number keeps.
    static @Nullable String plain(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
