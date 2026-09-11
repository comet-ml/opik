package com.comet.opik.domain;

import com.comet.opik.api.ExportParams;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.domain.export.ExportSource;
import com.comet.opik.domain.export.ExportSourceRegistry;
import com.comet.opik.infrastructure.ExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service responsible for generating CSV files from dataset items and uploading them to S3/MinIO.
 */
@ImplementedBy(CsvExportProcessorImpl.class)
public interface CsvExportProcessor {

    /**
     * Generates a CSV file and uploads it to S3/MinIO.
     *
     * @param params What to export, shaped per export type
     * @return A Mono containing the export result with file path and expiration
     */
    Mono<CsvExportResult> generateAndUploadCsv(ExportParams params);

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
class CsvExportProcessorImpl implements CsvExportProcessor {

    private final @NonNull ExportSourceRegistry sourceRegistry;
    private final @NonNull FileService fileService;
    private final @NonNull @Config("datasetExport") ExportConfig exportConfig;

    private static final String CSV_CONTENT_TYPE = "text/csv";
    private static final int S3_MIN_PART_SIZE = 5242880; // 5 MB - S3 requirement for non-final parts

    @Override
    public Mono<CsvExportResult> generateAndUploadCsv(@NonNull ExportParams params) {
        ExportSource source = sourceRegistry.get(params);

        log.info("Starting CSV generation for '{}' export", params.exportType());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Step 1: ask the source which columns it will produce
            return source.discoverColumns(params)
                    .map(LinkedHashSet::new)
                    .flatMap(columns -> {
                        log.info("Discovered '{}' columns for '{}' export", columns.size(), params.exportType());

                        // Step 2: Generate CSV and upload using streaming approach
                        return generateAndUploadCsvStreaming(source, params, columns, workspaceId)
                                .map(filePath -> {
                                    // Step 3: Calculate expiration time
                                    Duration ttl = exportConfig.getDefaultTtl().toJavaDuration();
                                    Instant expiresAt = Instant.now().plus(ttl);

                                    log.info("CSV export completed for '{}', expires at: '{}'",
                                            params.exportType(), expiresAt);

                                    return CsvExportResult.builder()
                                            .filePath(filePath)
                                            .expiresAt(expiresAt)
                                            .build();
                                });
                    });
        });
    }

    /**
     * Generates CSV and uploads it directly to S3/MinIO using multipart upload.
     * This approach streams data reactively to S3/MinIO without loading everything into memory.
     * Data is buffered only until reaching the minimum part size, then uploaded immediately.
     * Buffers are discarded after upload to allow garbage collection.
     * Parts metadata is kept minimal (only partNumber and eTag) to avoid memory issues.
     *
     * @param params      What to export
     * @param columns     The ordered set of column names
     * @param workspaceId The workspace ID
     * @return A Mono containing the S3/MinIO key of the uploaded file
     */
    private Mono<String> generateAndUploadCsvStreaming(@NonNull ExportSource source, @NonNull ExportParams params,
            @NonNull Set<String> columns, @NonNull String workspaceId) {
        // Enforce S3's hard minimum of 5MB for non-final parts
        int minPartSize = Math.max(exportConfig.getMinPartSize(), S3_MIN_PART_SIZE);
        int maxPartSize = Math.max(exportConfig.getMaxPartSize(), minPartSize);
        int itemBatchSize = exportConfig.getItemBatchSize();

        log.debug("Generating and uploading CSV for '{}' with '{}' columns using multipart upload. " +
                "Config: minPartSize='{}', maxPartSize='{}', itemBatchSize='{}'",
                params.exportType(), columns.size(), minPartSize, maxPartSize, itemBatchSize);

        String filePath = generateFilePath(workspaceId, params);
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
                    // List to collect part metadata (only partNumber and eTag - minimal memory footprint)
                    // This is necessary for S3 CompleteMultipartUpload API
                    List<MultipartUploadPart> uploadedParts = new CopyOnWriteArrayList<>();

                    // Use AtomicReference to hold the current buffer - allows replacing with new buffer
                    // after each upload so old buffer can be garbage collected
                    AtomicReference<ByteArrayOutputStream> bufferRef = new AtomicReference<>(
                            createNewBuffer(headerBytes, maxPartSize));

                    // Stream items reactively and process in batches
                    return source.streamRows(params, itemBatchSize)
                            .map(row -> convertRowToCsv(row, columnList))
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
                                            currentPartNumber, partData.length, params.exportType());

                                    return uploadPartAndCollect(filePath, uploadId, currentPartNumber, partData,
                                            uploadedParts, totalPartsUploaded)
                                            .then(Mono.just(uploadedParts));
                                }
                                // If buffer is empty but we have header bytes, upload them
                                if (uploadedParts.isEmpty() && headerBytes.length > 0) {
                                    int currentPartNumber = partNumber.getAndIncrement();
                                    log.debug("Uploading header-only part '{}' of '{}' bytes for empty dataset '{}'",
                                            currentPartNumber, headerBytes.length, params.exportType());

                                    return uploadPartAndCollect(filePath, uploadId, currentPartNumber, headerBytes,
                                            uploadedParts, totalPartsUploaded)
                                            .then(Mono.just(uploadedParts));
                                }
                                return Mono.just(uploadedParts);
                            }))
                            .flatMap(parts -> {
                                if (parts.isEmpty()) {
                                    // Edge case: no columns and no items - upload empty CSV
                                    log.warn("No parts to upload for dataset '{}', uploading empty file",
                                            params.exportType());
                                    return abortMultipartUpload(filePath, uploadId)
                                            .then(uploadEmptyFile(filePath));
                                }
                                log.info("Completing multipart upload for dataset '{}' with '{}' parts",
                                        params.exportType(), parts.size());
                                return completeMultipartUpload(filePath, uploadId, parts)
                                        .thenReturn(filePath);
                            })
                            .doOnSuccess(path -> log.info(
                                    "Successfully completed upload for dataset '{}', file: '{}', totalParts: '{}'",
                                    params.exportType(), path, totalPartsUploaded.get()))
                            .onErrorResume(error -> {
                                log.error(
                                        "Failed to generate and upload CSV for dataset '{}', aborting multipart upload",
                                        params.exportType(), error);
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
    /**
     * Writes a source-produced row in column order; absent keys become empty cells.
     */
    private byte[] convertRowToCsv(SequencedMap<String, String> row, List<String> columnList) {
        return writeCsv(csvPrinter -> {
            List<String> cells = new ArrayList<>(columnList.size());

            for (String column : columnList) {
                cells.add(row.getOrDefault(column, ""));
            }

            csvPrinter.printRecord(cells);
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
     * @param params      What to export
     * @return The S3/MinIO key
     */
    private String generateFilePath(@NonNull String workspaceId, @NonNull ExportParams params) {
        String timestamp = Instant.now().toString().replace(":", "-");
        // The params hash stands in for "which rows" without the path having to know what scopes this export type.
        return String.format("exports/%s/%s/%s/export_%s.csv", workspaceId, params.exportType(),
                params.canonicalHash(), timestamp);
    }
}
