package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Builder(toBuilder = true)
public record ModelPriceRow(
        @NonNull UUID id,
        @NonNull String modelName,
        @NonNull String provider,
        @NonNull CustomModelPrice.Kind kind,
        @Nullable BigDecimal inputPerMillion,
        @Nullable BigDecimal outputPerMillion,
        @Nullable BigDecimal cacheReadPerMillion,
        @Nullable BigDecimal cacheWritePerMillion,
        @Nullable String aliasModelName,
        @Nullable String aliasProvider,
        @Nullable String note,
        @Nullable Instant createdAt,
        @Nullable String createdBy,
        @Nullable Instant lastUpdatedAt,
        @Nullable String lastUpdatedBy) {

    static ModelPriceRow of(@NonNull UUID id, @NonNull CustomModelPriceValidator.ValidRow row) {
        CustomModelPrice price = row.price();
        return ModelPriceRow.builder()
                .id(id)
                .modelName(price.modelName())
                .provider(price.provider())
                .kind(price.kind())
                .inputPerMillion(price.inputPerMillion())
                .outputPerMillion(price.outputPerMillion())
                .cacheReadPerMillion(price.cacheReadPerMillion())
                .cacheWritePerMillion(price.cacheWritePerMillion())
                .aliasModelName(price.aliasModelName())
                .aliasProvider(price.aliasProvider())
                .note(row.note())
                .build();
    }

    public CustomModelPrice toPrice() {
        return CustomModelPrice.builder()
                .modelName(modelName)
                .provider(provider)
                .kind(kind)
                .inputPerMillion(inputPerMillion)
                .outputPerMillion(outputPerMillion)
                .cacheReadPerMillion(cacheReadPerMillion)
                .cacheWritePerMillion(cacheWritePerMillion)
                .aliasModelName(aliasModelName)
                .aliasProvider(aliasProvider)
                .build();
    }

    // The column spells the kind as the API does.
    public String kindValue() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    // Prices compare by value: MySQL hands back 1.0000000000 for a stored 1.00, which is no change.
    boolean sameContentAs(@NonNull ModelPriceRow other) {
        return kind == other.kind
                && samePrice(inputPerMillion, other.inputPerMillion)
                && samePrice(outputPerMillion, other.outputPerMillion)
                && samePrice(cacheReadPerMillion, other.cacheReadPerMillion)
                && samePrice(cacheWritePerMillion, other.cacheWritePerMillion)
                && Objects.equals(aliasModelName, other.aliasModelName)
                && Objects.equals(aliasProvider, other.aliasProvider)
                && Objects.equals(note, other.note);
    }

    private static boolean samePrice(@Nullable BigDecimal left, @Nullable BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
