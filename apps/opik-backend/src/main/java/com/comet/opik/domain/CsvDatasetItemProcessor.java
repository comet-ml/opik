package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetStatus;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * Processes CSV files for dataset items.
 * Handles CSV parsing, validation, and batch processing.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CsvDatasetItemProcessor {

    private static final int LOG_FREQUENCY_MULTIPLIER = 10;

    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull DatasetService datasetService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull BatchOperationsConfig batchOperationsConfig;

    private int getBatchSize() {
        return batchOperationsConfig.getDatasets().getCsvBatchSize();
    }

    private int getLogFrequency() {
        return getBatchSize() * LOG_FREQUENCY_MULTIPLIER;
    }

    /**
     * Processes uploaded CSV file by buffering to temp storage and processing asynchronously.
     * This method buffers the stream synchronously (to avoid connection timeout issues),
     * validates the CSV headers, then processes the file asynchronously with automatic cleanup.
     *
     * @param inputStream CSV file input stream from upload
     * @param datasetId Dataset ID
     * @param workspaceId Workspace UUID
     * @param userName User name
     * @param visibility Visibility setting
     * @throws BadRequestException if buffering fails or CSV validation fails
     */
    public void processUploadedCsv(InputStream inputStream, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        log.info("Processing CSV upload for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);

        // Buffer the stream to a temporary file BEFORE returning HTTP response
        // This ensures the stream is fully read before the connection times out
        Path tempFile;
        try {
            tempFile = bufferToTempFile(inputStream);
        } catch (IOException e) {
            log.error("Failed to buffer CSV file to temp storage for dataset '{}'", datasetId, e);
            throw new InternalServerErrorException("Failed to process CSV file");
        }

        // Validate CSV headers synchronously BEFORE starting async processing
        // This ensures validation errors are returned to the client immediately
        try {
            validateCsvHeaders(tempFile);
        } catch (Exception e) {
            deleteTempFile(tempFile);
            throw e;
        }

        // Set status to PROCESSING before starting async processing
        datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.PROCESSING);

        // Now process asynchronously with automatic cleanup
        log.info("Starting asynchronous CSV processing for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);
        validateAndProcessCsvFromFile(tempFile, datasetId, workspaceId, userName, visibility)
                .doOnError(error -> {
                    log.error("CSV processing failed for dataset '{}'", datasetId, error);
                    datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.FAILED);
                    deleteTempFile(tempFile);
                })
                .doOnSuccess(totalItems -> {
                    log.info("CSV processing completed for dataset '{}', total items: '{}'",
                            datasetId, totalItems);
                    datasetService.updateStatus(datasetId, workspaceId, DatasetStatus.COMPLETED);
                    deleteTempFile(tempFile);
                })
                .subscribe(null, error -> log.error("Subscription error during CSV processing for dataset '{}'",
                        datasetId, error));
    }

    /**
     * Buffers input stream to a temporary file.
     *
     * @param inputStream Input stream to buffer
     * @return Path to temporary file
     * @throws IOException if buffering fails
     */
    private Path bufferToTempFile(InputStream inputStream) throws IOException {
        Path tempFile = Files.createTempFile("csv-upload-", ".csv");
        try {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.debug("CSV file buffered to temp file: '{}', size: '{}' bytes",
                    tempFile, Files.size(tempFile));
            return tempFile;
        } catch (IOException e) {
            deleteTempFile(tempFile);
            throw e;
        }
    }

    /**
     * Validates CSV headers synchronously to catch errors before async processing.
     * This ensures validation errors are returned to the client immediately.
     *
     * @param tempFile Temporary file containing CSV data
     * @throws BadRequestException if CSV headers are invalid
     */
    private void validateCsvHeaders(Path tempFile) {
        try (BOMInputStream bomStream = BOMInputStream.builder()
                .setInputStream(Files.newInputStream(tempFile))
                .get();
                InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
                CSVParser parser = createCsvParser(reader)) {

            List<String> headers = parser.getHeaderNames();

            if (headers.isEmpty()) {
                throw new BadRequestException("CSV file must contain headers");
            }

            // Check for empty or blank headers
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                if (StringUtils.isBlank(header)) {
                    throw new BadRequestException(
                            String.format("CSV header at position %d is empty or blank. All headers must have a name.",
                                    i + 1));
                }
            }

            log.debug("CSV headers validated successfully: {}", headers);
        } catch (IllegalArgumentException e) {
            // Apache Commons CSV throws IllegalArgumentException for empty headers
            log.error("Invalid CSV headers", e);
            String message = e.getMessage();
            if (message != null && message.contains("header name is missing")) {
                throw new BadRequestException("CSV contains empty header names. All column headers must have a name.");
            }
            throw new BadRequestException("Invalid CSV format");
        } catch (IOException e) {
            log.error("Failed to validate CSV headers", e);
            throw new BadRequestException("Failed to read CSV file");
        }
    }

    /**
     * Deletes temporary file with error handling.
     *
     * @param tempFile Path to temporary file
     */
    private void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
            log.debug("Deleted temp file: '{}'", tempFile);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: '{}'", tempFile, e);
        }
    }

    /**
     * Validates and processes CSV file from a temporary file.
     * The file is streamed and processed in batches to minimize memory usage.
     * Only one batch is kept in memory at a time.
     *
     * @param tempFile Temporary file containing CSV data
     * @param datasetId Dataset ID
     * @param workspaceId Workspace UUID
     * @param userName User name
     * @param visibility Visibility setting
     * @return Mono that completes when processing is done
     */
    private Mono<Long> validateAndProcessCsvFromFile(Path tempFile, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        log.debug("Starting CSV validation and batch processing for dataset '{}' from temp file: '{}'",
                datasetId, tempFile);

        // Verify dataset exists before processing
        return verifyDatasetExists(datasetId, workspaceId, visibility)
                .then(Mono.defer(() -> processFileFromPath(tempFile, datasetId, workspaceId, userName, visibility)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> verifyDatasetExists(UUID datasetId, String workspaceId, Visibility visibility) {
        return Mono.fromCallable(() -> {
            datasetService.findById(datasetId, workspaceId, visibility);
            log.debug("Dataset '{}' verified", datasetId);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Long> processFileFromPath(Path tempFile, UUID datasetId, String workspaceId, String userName,
            Visibility visibility) {
        return Mono.fromCallable(() -> {
            try (BOMInputStream bomStream = BOMInputStream.builder()
                    .setInputStream(Files.newInputStream(tempFile))
                    .get();
                    InputStreamReader reader = new InputStreamReader(bomStream, StandardCharsets.UTF_8);
                    CSVParser parser = createCsvParser(reader)) {

                List<String> headers = parser.getHeaderNames();
                if (headers.isEmpty()) {
                    throw new BadRequestException("CSV file must contain headers");
                }

                int batchSize = getBatchSize();
                int logFrequency = getLogFrequency();

                List<DatasetItem> batch = new ArrayList<>(batchSize);
                long totalProcessed = 0;
                int batchNumber = 0;
                long recordCount = 0;
                boolean hasDataRows = false;

                for (CSVRecord record : parser) {
                    hasDataRows = true;
                    recordCount++;

                    if (recordCount % logFrequency == 0) {
                        log.debug("Processing record '{}' for dataset '{}'", recordCount, datasetId);
                    }

                    Map<String, JsonNode> dataMap = headers.stream()
                            .collect(Collectors.toMap(
                                    header -> header,
                                    header -> {
                                        String value = record.get(header);
                                        return StringUtils.isNotEmpty(value)
                                                ? JsonUtils.valueToTree(value)
                                                : JsonUtils.valueToTree("");
                                    }));

                    DatasetItem item = DatasetItem.builder()
                            .id(idGenerator.generateId())
                            .source(DatasetItemSource.MANUAL)
                            .data(dataMap)
                            .build();

                    batch.add(item);

                    if (batch.size() >= batchSize) {
                        batchNumber++;
                        log.debug("Saving batch '{}' for dataset '{}', batch size: '{}'",
                                batchNumber, datasetId, batch.size());
                        totalProcessed += saveBatch(batch, datasetId, workspaceId, userName, visibility);
                        batch.clear();
                    }
                }

                if (!hasDataRows) {
                    throw new BadRequestException("CSV file contains no data rows");
                }

                // Save remaining items
                if (!batch.isEmpty()) {
                    batchNumber++;
                    log.debug("Saving final batch '{}' for dataset '{}', batch size: '{}'",
                            batchNumber, datasetId, batch.size());
                    totalProcessed += saveBatch(batch, datasetId, workspaceId, userName, visibility);
                }

                log.info("Completed CSV processing for dataset '{}', total items: '{}', batches: '{}'",
                        datasetId, totalProcessed, batchNumber);
                return totalProcessed;
            } catch (IOException e) {
                log.warn("Failed to process CSV file for dataset '{}'", datasetId, e);
                throw new BadRequestException("Failed to read CSV file");
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private long saveBatch(List<DatasetItem> items, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        datasetItemService.saveBatch(datasetId, items)
                .contextWrite(ctx -> setRequestContext(ctx, workspaceId, userName, visibility))
                .block();

        return items.size();
    }

    private CSVParser createCsvParser(InputStreamReader reader) throws IOException {
        return CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build()
                .parse(reader);
    }
}
