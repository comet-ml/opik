package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service responsible for generating CSV files from dataset items and uploading them to S3/MinIO.
 */
@ImplementedBy(CsvDatasetExportProcessorImpl.class)
public interface CsvDatasetExportProcessor {

    /**
     * Generates a CSV file from dataset items and uploads it to S3/MinIO.
     *
     * @param datasetId The dataset ID to export
     * @return A Mono containing the export result with file path and expiration
     */
    Mono<CsvExportResult> generateAndUploadCsv(UUID datasetId);

    /**
     * Result of CSV export containing file metadata
     */
    @Builder
    record CsvExportResult(
            @NonNull String filePath,
            @NonNull Instant expiresAt) {
    }
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CsvDatasetExportProcessorImpl implements CsvDatasetExportProcessor {

    private final @NonNull DatasetItemDAO datasetItemDao;
    private final @NonNull FileService fileService;
    private final @NonNull @Config("datasetExport") DatasetExportConfig exportConfig;

    private static final String CSV_CONTENT_TYPE = "text/csv";
    private static final int S3_MIN_PART_SIZE = 5242880; // 5 MB - S3 requirement for non-final parts

    @Override
    public Mono<CsvExportResult> generateAndUploadCsv(@NonNull UUID datasetId) {
        log.info("Starting CSV generation for dataset: '{}'", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Step 1: Discover all columns in the dataset
            return discoverColumns(datasetId)
                    .flatMap(columns -> {
                        log.info("Discovered '{}' columns for dataset '{}'", columns.size(), datasetId);

                        // Step 2: Generate CSV and upload using streaming approach
                        return generateAndUploadCsvStreaming(datasetId, columns, workspaceId)
                                .map(filePath -> {
                                    // Step 3: Calculate expiration time
                                    Duration ttl = exportConfig.getDefaultTtl().toJavaDuration();
                                    Instant expiresAt = Instant.now().plus(ttl);

                                    log.info("CSV export completed for dataset '{}', expires at: '{}'",
                                            datasetId, expiresAt);

                                    return CsvExportResult.builder()
                                            .filePath(filePath)
                                            .expiresAt(expiresAt)
                                            .build();
                                });
                    });
        });
    }

    /**
     * Discovers all unique column names from the dataset items.
     * Columns are returned in the order provided by the DAO (LinkedHashMap preserves insertion order).
     *
     * @param datasetId The dataset ID
     * @return A Mono containing an ordered set of column names
     */
    private Mono<Set<String>> discoverColumns(@NonNull UUID datasetId) {
        log.debug("Discovering columns for dataset: '{}'", datasetId);

        return datasetItemDao.getColumns(datasetId)
                .map(columnsMap -> {
                    // LinkedHashMap from DAO preserves insertion order
                    Set<String> columnNames = new LinkedHashSet<>(columnsMap.keySet());
                    log.debug("Found columns for dataset '{}': '{}'", datasetId, columnNames);
                    return columnNames;
                });
    }

    /**
     * Generates CSV and uploads it directly to S3/MinIO using multipart upload.
     * This approach streams data reactively to S3/MinIO without loading everything into memory.
     * Data is buffered only until reaching the minimum part size, then uploaded immediately.
     * Buffers are discarded after upload to allow garbage collection.
     * Parts metadata is kept minimal (only partNumber and eTag) to avoid memory issues.
     *
     * @param datasetId   The dataset ID
     * @param columns     The ordered set of column names
     * @param workspaceId The workspace ID
     * @return A Mono containing the S3/MinIO key of the uploaded file
     */
    private Mono<String> generateAndUploadCsvStreaming(@NonNull UUID datasetId, @NonNull Set<String> columns,
            @NonNull String workspaceId) {
        // Enforce S3's hard minimum of 5MB for non-final parts
        int minPartSize = Math.max(exportConfig.getMinPartSize(), S3_MIN_PART_SIZE);
        int maxPartSize = Math.max(exportConfig.getMaxPartSize(), minPartSize);
        int itemBatchSize = exportConfig.getItemBatchSize();

        log.debug("Generating and uploading CSV for dataset '{}' with '{}' columns using multipart upload. " +
                "Config: minPartSize='{}', maxPartSize='{}', itemBatchSize='{}'",
                datasetId, columns.size(), minPartSize, maxPartSize, itemBatchSize);

        String filePath = generateFilePath(workspaceId, datasetId);
        List<String> columnList = new ArrayList<>(columns);

        // Create CSV header
        byte[] headerBytes = createCsvHeader(columns);

        // Start multipart upload (blocking operation wrapped in Mono)
        return startMultipartUpload(filePath)
                .flatMap(uploadId -> {
                    log.info("Started multipart upload for key: '{}', uploadId: '{}'", filePath, uploadId);

                    // Create state holders for streaming - using AtomicReference for thread safety
                    AtomicInteger partNumber = new AtomicInteger(1);
                    AtomicInteger totalPartsUploaded = new AtomicInteger(0);
                    AtomicReference<UUID> lastRetrievedId = new AtomicReference<>(null);

                    // List to collect part metadata (only partNumber and eTag - minimal memory footprint)
                    // This is necessary for S3 CompleteMultipartUpload API
                    List<MultipartUploadPart> uploadedParts = new CopyOnWriteArrayList<>();

                    // Use AtomicReference to hold the current buffer - allows replacing with new buffer
                    // after each upload so old buffer can be garbage collected
                    AtomicReference<ByteArrayOutputStream> bufferRef = new AtomicReference<>(
                            createNewBuffer(headerBytes, maxPartSize));

                    // Stream items reactively and process in batches
                    return streamAllItems(datasetId, lastRetrievedId, itemBatchSize)
                            .map(item -> convertItemToCsvRow(item, columnList))
                            .buffer(itemBatchSize)
                            .concatMap(rows -> {
                                ByteArrayOutputStream currentBuffer = bufferRef.get();
                                List<Mono<Void>> uploadMonos = new ArrayList<>();

                                // Accumulate rows into buffer
                                for (byte[] row : rows) {
                                    appendToBuffer(currentBuffer, row);

                                    // If buffer exceeds max part size, upload immediately
                                    // This prevents memory issues with very large rows
                                    if (currentBuffer.size() >= maxPartSize) {
                                        byte[] partData = currentBuffer.toByteArray();
                                        int currentPartNumber = partNumber.getAndIncrement();

                                        // Create upload Mono and add to list
                                        Mono<Void> uploadMono = uploadPartAndCollect(filePath, uploadId,
                                                currentPartNumber, partData,
                                                uploadedParts, totalPartsUploaded);
                                        uploadMonos.add(uploadMono);

                                        // Create new buffer for next part - old buffer will be GC'd
                                        currentBuffer = createNewBuffer(null, maxPartSize);
                                        bufferRef.set(currentBuffer);
                                    }
                                }

                                // If we have uploads to perform, execute them sequentially
                                if (!uploadMonos.isEmpty()) {
                                    return Flux.concat(uploadMonos).then(Mono.just(true));
                                }

                                // Check if buffer is ready for upload (>= minPartSize)
                                if (currentBuffer.size() >= minPartSize) {
                                    byte[] partData = currentBuffer.toByteArray();
                                    int currentPartNumber = partNumber.getAndIncrement();

                                    // Create new buffer for next part - old buffer will be GC'd
                                    bufferRef.set(createNewBuffer(null, maxPartSize));

                                    return uploadPartAndCollect(filePath, uploadId, currentPartNumber, partData,
                                            uploadedParts, totalPartsUploaded)
                                            .thenReturn(true);
                                }
                                return Mono.just(true);
                            })
                            .then(Mono.defer(() -> {
                                // Upload final part - always upload remaining data (even if just header)
                                // S3 multipart upload requires at least one part
                                ByteArrayOutputStream finalBuffer = bufferRef.get();
                                byte[] partData = finalBuffer.toByteArray();

                                // Clear reference to allow GC
                                bufferRef.set(null);

                                if (partData.length > 0) {
                                    int currentPartNumber = partNumber.getAndIncrement();
                                    log.debug("Uploading final part '{}' of '{}' bytes for dataset '{}'",
                                            currentPartNumber, partData.length, datasetId);

                                    return uploadPartAndCollect(filePath, uploadId, currentPartNumber, partData,
                                            uploadedParts, totalPartsUploaded)
                                            .then(Mono.just(uploadedParts));
                                }
                                // If buffer is empty but we have header bytes, upload them
                                if (uploadedParts.isEmpty() && headerBytes.length > 0) {
                                    int currentPartNumber = partNumber.getAndIncrement();
                                    log.debug("Uploading header-only part '{}' of '{}' bytes for empty dataset '{}'",
                                            currentPartNumber, headerBytes.length, datasetId);

                                    return uploadPartAndCollect(filePath, uploadId, currentPartNumber, headerBytes,
                                            uploadedParts, totalPartsUploaded)
                                            .then(Mono.just(uploadedParts));
                                }
                                return Mono.just(uploadedParts);
                            }))
                            .flatMap(parts -> {
                                if (parts.isEmpty()) {
                                    // Edge case: no columns and no items - upload empty CSV
                                    log.warn("No parts to upload for dataset '{}', uploading empty file", datasetId);
                                    return abortMultipartUpload(filePath, uploadId)
                                            .then(uploadEmptyFile(filePath));
                                }
                                log.info("Completing multipart upload for dataset '{}' with '{}' parts",
                                        datasetId, parts.size());
                                return completeMultipartUpload(filePath, uploadId, parts)
                                        .thenReturn(filePath);
                            })
                            .doOnSuccess(path -> log.info(
                                    "Successfully completed upload for dataset '{}', file: '{}', totalParts: '{}'",
                                    datasetId, path, totalPartsUploaded.get()))
                            .onErrorResume(error -> {
                                log.error(
                                        "Failed to generate and upload CSV for dataset '{}', aborting multipart upload",
                                        datasetId, error);
                                return abortMultipartUpload(filePath, uploadId)
                                        .then(Mono.error(new InternalServerErrorException(
                                                "Failed to export dataset. Please try again later.")));
                            });
                });
    }

    /**
     * Creates a new buffer with optional initial data.
     * Buffer is created with appropriate initial capacity to minimize reallocations.
     */
    private ByteArrayOutputStream createNewBuffer(byte[] initialData, int maxSize) {
        // Initial capacity is min of maxSize or 10MB to avoid over-allocation
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxSize, 10 * 1024 * 1024));
        if (initialData != null && initialData.length > 0) {
            try {
                buffer.write(initialData);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to initialize buffer", e);
            }
        }
        return buffer;
    }

    /**
     * Appends byte data directly to the buffer.
     */
    private void appendToBuffer(ByteArrayOutputStream buffer, byte[] data) {
        try {
            buffer.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to buffer", e);
        }
    }

    /**
     * Uploads a part and collects its metadata into the parts list.
     * This method ensures parts are uploaded immediately and only minimal metadata is kept.
     */
    private Mono<Void> uploadPartAndCollect(String filePath, String uploadId, int partNumber, byte[] data,
            List<MultipartUploadPart> uploadedParts, AtomicInteger totalPartsUploaded) {
        return uploadPart(filePath, uploadId, partNumber, data)
                .doOnNext(part -> {
                    uploadedParts.add(part);
                    totalPartsUploaded.incrementAndGet();
                })
                .then();
    }

    /**
     * Streams all dataset items using cursor-based pagination.
     * Repeatedly fetches pages until an empty page or partial page is returned.
     */
    private Flux<DatasetItem> streamAllItems(UUID datasetId, AtomicReference<UUID> lastRetrievedId, int batchSize) {
        return Flux.defer(() -> {
            return datasetItemDao.getItems(datasetId, batchSize, lastRetrievedId.get())
                    .collectList()
                    .flatMapMany(items -> {
                        if (items.isEmpty()) {
                            // No more items, stop pagination
                            return Flux.empty();
                        }

                        // Update cursor to last item
                        lastRetrievedId.set(items.get(items.size() - 1).id());

                        // Emit all items from this page
                        Flux<DatasetItem> pageFlux = Flux.fromIterable(items);

                        // If we got a full page, there might be more data - recurse
                        if (items.size() == batchSize) {
                            return pageFlux.concatWith(streamAllItems(datasetId, lastRetrievedId, batchSize));
                        }

                        // Partial page means this is the last page
                        return pageFlux;
                    });
        });
    }

    /**
     * Creates the CSV header row as bytes.
     */
    private byte[] createCsvHeader(Set<String> columns) {
        if (columns.isEmpty()) {
            return new byte[0];
        }

        return writeCsv(csvPrinter -> csvPrinter.printRecord(columns));
    }

    /**
     * Converts a DatasetItem to a CSV row as bytes.
     * Returns raw bytes to avoid unnecessary String allocation and subsequent getBytes() call.
     */
    private byte[] convertItemToCsvRow(DatasetItem item, List<String> columnList) {
        return writeCsv(csvPrinter -> {
            List<String> row = new ArrayList<>(columnList.size());
            Map<String, JsonNode> data = item.data();

            for (String column : columnList) {
                JsonNode value = data.get(column);
                if (value == null || value.isNull()) {
                    row.add("");
                } else if (value.isTextual()) {
                    row.add(value.asText());
                } else {
                    row.add(JsonUtils.writeValueAsString(value));
                }
            }

            csvPrinter.printRecord(row);
        });
    }

    /**
     * Helper method to write CSV data using a consumer that provides the record-writing logic.
     * Handles all the plumbing: ByteArrayOutputStream, OutputStreamWriter, CSVPrinter creation,
     * flushing, and exception wrapping.
     *
     * @param writer Consumer that writes records to the CSVPrinter
     * @return The CSV data as a byte array
     * @throws UncheckedIOException if an I/O error occurs
     */
    private byte[] writeCsv(ThrowingConsumer<CSVPrinter> writer) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
                CSVPrinter csvPrinter = new CSVPrinter(osw, CSVFormat.DEFAULT)) {

            writer.accept(csvPrinter);
            csvPrinter.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV data", e);
        }
    }

    /**
     * Functional interface for operations that may throw IOException.
     */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }

    /**
     * Starts a multipart upload (blocking S3 operation wrapped in Mono).
     */
    private Mono<String> startMultipartUpload(String filePath) {
        return Mono.fromCallable(() -> fileService.createMultipartUpload(filePath, CSV_CONTENT_TYPE).uploadId())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Uploads a part to S3 (blocking operation wrapped in Mono).
     */
    private Mono<MultipartUploadPart> uploadPart(String filePath, String uploadId, int partNumber, byte[] data) {
        return Mono.fromCallable(() -> {
            log.debug("Uploading part '{}' of '{}' bytes for key: '{}'", partNumber, data.length, filePath);
            String eTag = fileService.uploadPart(filePath, uploadId, partNumber, data);
            return MultipartUploadPart.builder()
                    .partNumber(partNumber)
                    .eTag(eTag)
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Completes the multipart upload (blocking operation wrapped in Mono).
     */
    private Mono<Void> completeMultipartUpload(String filePath, String uploadId, List<MultipartUploadPart> parts) {
        return Mono.fromRunnable(() -> {
            log.debug("Completing multipart upload for key: '{}', parts: '{}'", filePath, parts.size());
            fileService.completeMultipartUpload(filePath, uploadId, parts);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Aborts a multipart upload on error (blocking operation wrapped in Mono).
     */
    private Mono<Void> abortMultipartUpload(String filePath, String uploadId) {
        return Mono.fromRunnable(() -> {
            if (uploadId != null && !uploadId.isEmpty()) {
                fileService.abortMultipartUpload(filePath, uploadId);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Uploads an empty file using simple upload (for edge case of empty dataset with no columns).
     */
    private Mono<String> uploadEmptyFile(String filePath) {
        return Mono.fromCallable(() -> {
            log.debug("Uploading empty file for key: '{}'", filePath);
            fileService.upload(filePath, new byte[0], CSV_CONTENT_TYPE);
            return filePath;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Generates the S3/MinIO file path for the CSV export.
     *
     * @param workspaceId The workspace ID
     * @param datasetId   The dataset ID
     * @return The S3/MinIO key
     */
    private String generateFilePath(@NonNull String workspaceId, @NonNull UUID datasetId) {
        String timestamp = Instant.now().toString().replace(":", "-");
        return String.format("exports/%s/datasets/%s/export_%s.csv", workspaceId, datasetId, timestamp);
    }
}
