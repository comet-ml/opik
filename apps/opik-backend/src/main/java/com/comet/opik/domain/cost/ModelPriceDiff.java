package com.comet.opik.domain.cost;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** What replacing an organization's stored rows with a requested list changes, matched by model and provider. */
public record ModelPriceDiff(
        @NonNull List<ModelPriceRow> created,
        @NonNull List<Change> updated,
        @NonNull List<ModelPriceRow> deleted,
        @NonNull List<ModelPriceRow> unchanged) {

    public record Change(@NonNull ModelPriceRow before, @NonNull ModelPriceRow after) {
    }

    private record Key(String modelName, String provider) {
        static Key of(String modelName, String provider) {
            return new Key(modelName.toLowerCase(Locale.ROOT), provider.toLowerCase(Locale.ROOT));
        }
    }

    static ModelPriceDiff of(@NonNull List<ModelPriceRow> stored,
            @NonNull List<CustomModelPriceValidator.ValidRow> requested, @NonNull Supplier<UUID> newId) {
        Map<Key, ModelPriceRow> storedByKey = new LinkedHashMap<>();
        stored.forEach(row -> storedByKey.put(Key.of(row.modelName(), row.provider()), row));
        var created = new ArrayList<ModelPriceRow>();
        var updated = new ArrayList<Change>();
        var unchanged = new ArrayList<ModelPriceRow>();
        for (CustomModelPriceValidator.ValidRow row : requested) {
            ModelPriceRow existing = storedByKey.remove(Key.of(row.price().modelName(), row.price().provider()));
            if (existing == null) {
                created.add(ModelPriceRow.of(newId.get(), row));
                continue;
            }
            ModelPriceRow after = ModelPriceRow.of(existing.id(), row).toBuilder()
                    .createdAt(existing.createdAt())
                    .createdBy(existing.createdBy())
                    .build();
            if (existing.sameContentAs(after)) {
                unchanged.add(existing);
            } else {
                updated.add(new Change(existing, after));
            }
        }
        return new ModelPriceDiff(List.copyOf(created), List.copyOf(updated), List.copyOf(storedByKey.values()),
                List.copyOf(unchanged));
    }

    public boolean isEmpty() {
        return created.isEmpty() && updated.isEmpty() && deleted.isEmpty();
    }

    public List<ModelPriceRow> rowsAfter() {
        return Stream.of(unchanged.stream(), updated.stream().map(Change::after), created.stream())
                .flatMap(rows -> rows)
                .sorted(Comparator.comparing(ModelPriceRow::modelName).thenComparing(ModelPriceRow::provider))
                .toList();
    }
}
