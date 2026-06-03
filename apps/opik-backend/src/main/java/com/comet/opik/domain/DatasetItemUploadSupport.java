package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetStatus;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * Shared plumbing for dataset-item upload processors (CSV, JSON, …): temp-file buffering,
 * synchronous dataset existence checks, status transitions, async wire-up, and batch persistence.
 *
 * <p>Each upload follows the same flow:
 * <ol>
 *   <li>buffer the request body to a temp file ({@link #bufferToTempFile})</li>
 *   <li>format-specific head validation (in the caller)</li>
 *   <li>synchronously verify the dataset exists ({@link #verifyDatasetExists}) so a missing
 *       dataset surfaces as a 404 instead of a silent 202</li>
 *   <li>flip status to {@code PROCESSING} ({@link #markProcessing})</li>
 *   <li>run the format-specific pipeline through {@link #runAsync}, which flips to
 *       {@code COMPLETED}/{@code FAILED} and deletes the temp file on terminal signal</li>
 * </ol>
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetItemUploadSupport {

    private static final int LOG_FREQUENCY_MULTIPLIER = 10;

    private final @NonNull DatasetService datasetService;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull BatchOperationsConfig batchOperationsConfig;

    public int getBatchSize() {
        return batchOperationsConfig.getDatasets().getCsvBatchSize();
    }

    public int getLogFrequency() {
        return getBatchSize() * LOG_FREQUENCY_MULTIPLIER;
    }

    public Path bufferToTempFile(InputStream inputStream, String prefix, String suffix, String formatLabel)
            throws IOException {
        Path tempFile = Files.createTempFile(prefix, suffix);
        try {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.debug("{} file buffered to temp file: '{}', size: '{}' bytes",
                    formatLabel, tempFile, Files.size(tempFile));
            return tempFile;
        } catch (IOException e) {
            deleteTempFile(tempFile);
            throw e;
        }
    }

    public void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
            log.debug("Deleted temp file: '{}'", tempFile);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: '{}'", tempFile, e);
        }
    }

    /**
     * Synchronously verifies the dataset exists in the workspace; throws
     * {@link jakarta.ws.rs.NotFoundException} (→ HTTP 404) if it doesn't.
     */
    public void verifyDatasetExists(UUID datasetId, String workspaceId, Visibility visibility) {
        datasetService.findById(datasetId, workspaceId, visibility);
    }

    public void markProcessing(UUID datasetId, String workspaceId) {
        datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.PROCESSING);
    }

    /**
     * Subscribes to the format-specific pipeline and wires terminal handling:
     * flips dataset status to {@code COMPLETED}/{@code FAILED} and always deletes the temp file.
     */
    public void runAsync(Mono<Long> processing, Path tempFile, UUID datasetId, String workspaceId,
            String formatLabel) {
        processing
                .doOnError(error -> {
                    log.error("{} processing failed for dataset '{}'", formatLabel, datasetId, error);
                    datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.FAILED);
                    deleteTempFile(tempFile);
                })
                .doOnSuccess(totalItems -> {
                    log.info("{} processing completed for dataset '{}', total items: '{}'",
                            formatLabel, datasetId, totalItems);
                    datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.COMPLETED);
                    deleteTempFile(tempFile);
                })
                .subscribe(null, error -> log.debug(
                        "{} processing subscription error for dataset '{}' (already handled): {}",
                        formatLabel, datasetId, error.getMessage()));
    }

    public long saveBatch(List<DatasetItem> items, UUID datasetId, String workspaceId, String userName,
            Visibility visibility) {
        datasetItemService.saveBatch(datasetId, items)
                .contextWrite(ctx -> setRequestContext(ctx, workspaceId, userName, visibility))
                .block();
        return items.size();
    }

    /**
     * Creates a {@link BatchAccumulator} that buffers items up to {@link #getBatchSize()} and
     * flushes through {@link #saveBatch} automatically. Callers feed items via
     * {@link BatchAccumulator#add} and finalize with {@link BatchAccumulator#finish}, which
     * returns the total item count saved.
     */
    public BatchAccumulator newBatchAccumulator(UUID datasetId, String workspaceId, String userName,
            Visibility visibility) {
        return new BatchAccumulator(this, datasetId, workspaceId, userName, visibility);
    }

    /**
     * Buffers dataset items and flushes them in fixed-size batches through
     * {@link DatasetItemUploadSupport#saveBatch}. Not thread-safe — single-producer use only.
     */
    public static final class BatchAccumulator {

        private final DatasetItemUploadSupport support;
        private final int batchSize;
        private final UUID datasetId;
        private final String workspaceId;
        private final String userName;
        private final Visibility visibility;
        private final List<DatasetItem> buffer;
        private long totalProcessed = 0;
        private int batchNumber = 0;

        private BatchAccumulator(DatasetItemUploadSupport support, UUID datasetId, String workspaceId,
                String userName, Visibility visibility) {
            this.support = support;
            this.batchSize = support.getBatchSize();
            this.datasetId = datasetId;
            this.workspaceId = workspaceId;
            this.userName = userName;
            this.visibility = visibility;
            this.buffer = new ArrayList<>(batchSize);
        }

        /** Adds an item; flushes automatically when the buffer reaches the configured batch size. */
        public void add(DatasetItem item) {
            buffer.add(item);
            if (buffer.size() >= batchSize) {
                flush(false);
            }
        }

        /** Flushes any remaining items and returns the total saved. */
        public long finish() {
            if (!buffer.isEmpty()) {
                flush(true);
            }
            return totalProcessed;
        }

        public int batchNumber() {
            return batchNumber;
        }

        private void flush(boolean isFinal) {
            batchNumber++;
            log.debug("Saving {}batch '{}' for dataset '{}', batch size: '{}'",
                    isFinal ? "final " : "", batchNumber, datasetId, buffer.size());
            totalProcessed += support.saveBatch(buffer, datasetId, workspaceId, userName, visibility);
            buffer.clear();
        }
    }
}