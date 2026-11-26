package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceRow;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The reads and writes of an organization's price rows, on a handle the caller's transaction owns. A replace locks
 * the organization's list header first, so two saves of one list run one after the other.
 */
@RequiredArgsConstructor
class ModelPriceStore {

    private final @NonNull Supplier<UUID> newId;

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistorySnapshot(@NonNull String model, @NonNull String provider, @NonNull CustomModelPrice.Kind kind,
            @Nullable BigDecimal inputPrice, @Nullable BigDecimal outputPrice, @Nullable BigDecimal cacheReadPrice,
            @Nullable BigDecimal cacheWritePrice, @Nullable String aliasModel, @Nullable String aliasProvider,
            @Nullable String note) {

        static String jsonOf(ModelPriceRow row) {
            var snapshot = HistorySnapshot.builder()
                    .model(row.modelName())
                    .provider(row.provider())
                    .kind(row.kind())
                    .inputPrice(row.inputPerMillion())
                    .outputPrice(row.outputPerMillion())
                    .cacheReadPrice(row.cacheReadPerMillion())
                    .cacheWritePrice(row.cacheWritePerMillion())
                    .aliasModel(row.aliasModelName())
                    .aliasProvider(row.aliasProvider())
                    .note(row.note())
                    .build();
            return JsonUtils.valueToTree(snapshot).toString();
        }

        /** The stored snapshot as the API lists rows: string prices and alias_of, so history matches the list. */
        static @Nullable CustomModelPriceRow rowOf(@Nullable String json) {
            if (json == null) {
                return null;
            }
            var snapshot = JsonUtils.readValue(json, HistorySnapshot.class);
            var price = CustomModelPrice.builder()
                    .modelName(snapshot.model())
                    .provider(Objects.requireNonNullElse(snapshot.provider(), CustomModelPrice.ANY_PROVIDER))
                    .kind(snapshot.kind())
                    .inputPerMillion(snapshot.inputPrice())
                    .outputPerMillion(snapshot.outputPrice())
                    .cacheReadPerMillion(snapshot.cacheReadPrice())
                    .cacheWritePerMillion(snapshot.cacheWritePrice())
                    .aliasModelName(snapshot.aliasModel())
                    .aliasProvider(snapshot.aliasProvider())
                    .build();
            return CustomModelPriceRow.from(price).toBuilder().note(snapshot.note()).build();
        }
    }

    ModelPriceList load(@NonNull Handle handle, @NonNull String organizationId) {
        var dao = handle.attach(ModelPriceDAO.class);
        long version = dao.findList(organizationId).map(ModelPriceDAO.ListHeader::version).orElse(0L);
        List<CustomModelPrice> rows = dao.findRows(organizationId).stream().map(ModelPriceRow::toPrice).toList();
        return ModelPriceList.builder().organizationId(organizationId).version(version).rows(rows).build();
    }

    ModelPriceReplaceResult replaceAll(@NonNull Handle handle, @NonNull String organizationId,
            @NonNull List<CustomModelPriceValidator.ValidRow> rows, @Nullable Long expectedVersion,
            @NonNull String userName) {
        var dao = handle.attach(ModelPriceDAO.class);
        dao.lockOrCreateList(organizationId, userName);
        long current = dao.lockList(organizationId).version();
        checkExpectedVersion(expectedVersion, current);
        var diff = ModelPriceDiff.of(dao.findRows(organizationId), rows, newId);
        if (diff.isEmpty()) {
            return unsaved(current, diff);
        }
        long version = current + 1;
        if (!diff.created().isEmpty()) {
            dao.insertRows(organizationId, diff.created(), userName);
        }
        if (!diff.updated().isEmpty()) {
            dao.updateRows(organizationId, diff.updated().stream().map(ModelPriceDiff.Change::after).toList(),
                    userName);
        }
        if (!diff.deleted().isEmpty()) {
            dao.deleteRows(organizationId, diff.deleted().stream().map(ModelPriceRow::id).toList());
        }
        dao.insertHistory(organizationId, version, historyOf(diff), userName);
        dao.updateList(organizationId, version, diff.rowsAfter().size(), userName);
        return ModelPriceReplaceResult.builder().version(version).saved(true).diff(diff).warnings(List.of()).build();
    }

    ModelPriceReplaceResult previewReplaceAll(@NonNull Handle handle, @NonNull String organizationId,
            @NonNull List<CustomModelPriceValidator.ValidRow> rows, @Nullable Long expectedVersion) {
        var dao = handle.attach(ModelPriceDAO.class);
        long current = dao.findList(organizationId).map(ModelPriceDAO.ListHeader::version).orElse(0L);
        checkExpectedVersion(expectedVersion, current);
        return unsaved(current, ModelPriceDiff.of(dao.findRows(organizationId), rows, newId));
    }

    private static ModelPriceReplaceResult unsaved(long version, ModelPriceDiff diff) {
        return ModelPriceReplaceResult.builder().version(version).saved(false).diff(diff).warnings(List.of()).build();
    }

    private void checkExpectedVersion(@Nullable Long expectedVersion, long current) {
        if (expectedVersion != null && expectedVersion != current) {
            throw new ClientErrorException(
                    "The price list changed since version %d; it is now at version %d. Export it again and retry"
                            .formatted(expectedVersion, current),
                    Response.Status.CONFLICT);
        }
    }

    private List<ModelPriceDAO.HistoryEntry> historyOf(ModelPriceDiff diff) {
        var entries = new ArrayList<ModelPriceDAO.HistoryEntry>();
        diff.created().forEach(row -> entries.add(historyEntry(row.id(), ModelPriceHistoryAction.CREATED)
                .afterRow(HistorySnapshot.jsonOf(row))
                .build()));
        diff.updated().forEach(change -> entries.add(
                historyEntry(change.before().id(), ModelPriceHistoryAction.UPDATED)
                        .beforeRow(HistorySnapshot.jsonOf(change.before()))
                        .afterRow(HistorySnapshot.jsonOf(change.after()))
                        .build()));
        diff.deleted().forEach(row -> entries.add(historyEntry(row.id(), ModelPriceHistoryAction.DELETED)
                .beforeRow(HistorySnapshot.jsonOf(row))
                .build()));
        return entries;
    }

    private ModelPriceDAO.HistoryEntry.HistoryEntryBuilder historyEntry(UUID rowId, ModelPriceHistoryAction action) {
        return ModelPriceDAO.HistoryEntry.builder().id(newId.get()).rowId(rowId).action(action);
    }
}
