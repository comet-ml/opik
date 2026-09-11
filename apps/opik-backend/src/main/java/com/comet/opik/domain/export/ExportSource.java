package com.comet.opik.domain.export;

import com.comet.opik.api.ExportParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Everything an export type has to supply. The surrounding pipeline — job lifecycle, Redis hand-off, CSV writing,
 * multipart upload, TTL, cleanup and the download proxy — is written once and knows nothing about row shapes.
 *
 * <p>To make a new surface exportable: implement this interface alongside an {@link ExportParams} record, then bind
 * it in {@code ExportSourceModule}. Nothing else needs editing — not the schema, not the enum, not the processor.</p>
 */
public interface ExportSource {

    /**
     * Stable identifier for this export type. Persisted in {@code export_jobs.export_type} and used as the Jackson
     * type name for {@link #paramsType()}, so it must not change once rows exist.
     */
    String exportType();

    /**
     * The params record this source consumes. Registered as a Jackson subtype under {@link #exportType()}.
     */
    Class<? extends ExportParams> paramsType();

    /**
     * Column headers, in output order. Called once before streaming begins.
     */
    Mono<List<String>> discoverColumns(ExportParams params);

    /**
     * The rows to write, already flattened to column-keyed values. Implementations must stream lazily — keyset
     * pagination rather than offsets — because the whole point of the async pipeline is exports too large to hold
     * in memory.
     *
     * <p>Keys absent from a row are written as empty cells, so a source need not pad rows to the full column set.</p>
     */
    Flux<SequencedMap<String, String>> streamRows(ExportParams params, int batchSize);

    /**
     * Human-readable label for the progress panel and the download filename, snapshotted onto the job at creation.
     */
    default Mono<String> resolveResourceName(ExportParams params) {
        return Mono.just(exportType().toLowerCase());
    }

    /**
     * Convenience for implementations that need their own params type without an unchecked cast at every use.
     */
    default <T extends ExportParams> T cast(ExportParams params, Class<T> type) {
        if (!type.isInstance(params)) {
            throw new IllegalArgumentException(
                    "Export source '%s' cannot handle params of type '%s'".formatted(exportType(),
                            params == null ? "null" : params.getClass().getSimpleName()));
        }
        return type.cast(params);
    }

    /**
     * Row helper: the pipeline writes cells in column order, so sources build rows as ordered maps.
     */
    static SequencedMap<String, String> row(Map<String, String> values) {
        return new java.util.LinkedHashMap<>(values);
    }
}
