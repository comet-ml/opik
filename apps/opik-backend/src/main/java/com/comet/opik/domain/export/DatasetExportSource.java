package com.comet.opik.domain.export;

import com.comet.opik.api.DatasetExportParams;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExportParams;
import com.comet.opik.domain.DatasetItemDAO;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports the items of a dataset. Columns come from the dataset's own dynamic column map; rows are keyset-paginated
 * by item id so an arbitrarily large dataset streams in constant memory.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetExportSource implements ExportSource {

    private final @NonNull DatasetItemDAO datasetItemDao;

    @Override
    public String exportType() {
        return DatasetExportParams.TYPE;
    }

    @Override
    public Class<? extends ExportParams> paramsType() {
        return DatasetExportParams.class;
    }

    @Override
    public Mono<List<String>> discoverColumns(@NonNull ExportParams params) {
        UUID datasetId = cast(params, DatasetExportParams.class).datasetId();

        return datasetItemDao.getColumns(datasetId)
                // the DAO returns a LinkedHashMap, so this preserves the dataset's own column order
                .map(columnsMap -> List.copyOf(columnsMap.keySet()))
                .doOnNext(columns -> log.debug("Found '{}' columns for dataset '{}'", columns.size(), datasetId));
    }

    @Override
    public Flux<SequencedMap<String, String>> streamRows(@NonNull ExportParams params, int batchSize) {
        UUID datasetId = cast(params, DatasetExportParams.class).datasetId();

        return streamItems(datasetId, new AtomicReference<>(), batchSize).map(this::toRow);
    }

    private Flux<DatasetItem> streamItems(UUID datasetId, AtomicReference<UUID> lastRetrievedId, int batchSize) {
        return Flux.defer(() -> datasetItemDao.getItems(datasetId, batchSize, lastRetrievedId.get())
                .collectList()
                .flatMapMany(items -> {
                    if (items.isEmpty()) {
                        return Flux.empty();
                    }

                    lastRetrievedId.set(items.getLast().id());

                    Flux<DatasetItem> page = Flux.fromIterable(items);

                    // A full page means there may be more; a partial page is the last one.
                    return items.size() == batchSize
                            ? page.concatWith(streamItems(datasetId, lastRetrievedId, batchSize))
                            : page;
                }));
    }

    private SequencedMap<String, String> toRow(DatasetItem item) {
        Map<String, JsonNode> data = item.data();
        SequencedMap<String, String> row = new LinkedHashMap<>();

        if (data == null) {
            return row;
        }

        data.forEach((column, value) -> {
            if (value == null || value.isNull()) {
                row.put(column, "");
            } else if (value.isTextual()) {
                row.put(column, value.asText());
            } else {
                row.put(column, JsonUtils.writeValueAsString(value));
            }
        });

        return row;
    }
}
