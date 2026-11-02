package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The reads and writes of an organization's price rows, on a handle the caller's transaction owns. A replace locks
 * the organization's list header first, so two saves of one list run one after the other.
 */
@RequiredArgsConstructor
class ModelPriceStore {

    private final @NonNull Supplier<UUID> newId;

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record HistorySnapshot(String model, String provider, String kind, BigDecimal inputPrice,
            BigDecimal outputPrice, BigDecimal cacheReadPrice, BigDecimal cacheWritePrice, String aliasModel,
            String aliasProvider, String note) {

        static String jsonOf(ModelPriceRow row) {
            var snapshot = new HistorySnapshot(row.modelName(), row.provider(), row.kindValue(),
                    row.inputPerMillion(), row.outputPerMillion(), row.cacheReadPerMillion(),
                    row.cacheWritePerMillion(), row.aliasModelName(), row.aliasProvider(), row.note());
            return JsonUtils.valueToTree(snapshot).toString();
        }
    }

    ModelPriceList load(@NonNull Handle handle, @NonNull String organizationId) {
        var dao = handle.attach(ModelPriceDAO.class);
        long version = dao.findList(organizationId).map(ModelPriceDAO.ListHeader::version).orElse(0L);
        List<CustomModelPrice> rows = dao.findRows(organizationId).stream().map(ModelPriceRow::toPrice).toList();
        return new ModelPriceList(organizationId, version, rows);
    }

    ModelPriceReplaceResult replaceAll(@NonNull Handle handle, @NonNull String organizationId,
            @NonNull List<CustomModelPriceValidator.ValidRow> rows, @Nullable Long expectedVersion,
            @NonNull String userName) {
        var dao = handle.attach(ModelPriceDAO.class);
        dao.insertListIfMissing(organizationId, userName);
        long current = dao.lockList(organizationId).version();
        checkExpectedVersion(expectedVersion, current);
        var diff = ModelPriceDiff.of(dao.findRows(organizationId), rows, newId);
        if (diff.isEmpty()) {
            return new ModelPriceReplaceResult(current, false, diff, List.of());
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
        return new ModelPriceReplaceResult(version, true, diff, List.of());
    }

    ModelPriceReplaceResult previewReplaceAll(@NonNull Handle handle, @NonNull String organizationId,
            @NonNull List<CustomModelPriceValidator.ValidRow> rows, @Nullable Long expectedVersion) {
        var dao = handle.attach(ModelPriceDAO.class);
        long current = dao.findList(organizationId).map(ModelPriceDAO.ListHeader::version).orElse(0L);
        checkExpectedVersion(expectedVersion, current);
        return new ModelPriceReplaceResult(current, false,
                ModelPriceDiff.of(dao.findRows(organizationId), rows, newId), List.of());
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
        diff.created().forEach(row -> entries.add(
                new ModelPriceDAO.HistoryEntry(newId.get(), row.id(), "created", null, HistorySnapshot.jsonOf(row))));
        diff.updated().forEach(change -> entries.add(new ModelPriceDAO.HistoryEntry(newId.get(),
                change.before().id(), "updated", HistorySnapshot.jsonOf(change.before()),
                HistorySnapshot.jsonOf(change.after()))));
        diff.deleted().forEach(row -> entries.add(
                new ModelPriceDAO.HistoryEntry(newId.get(), row.id(), "deleted", HistorySnapshot.jsonOf(row), null)));
        return entries;
    }
}
