package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Visibility;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Processes CSV files for dataset items.
 * Handles CSV parsing, validation, and batch processing.
 *
 * <p>Shares temp-file buffering, dataset verification, status transitions, and batch
 * persistence with the JSON path via {@link DatasetItemUploadSupport}.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CsvDatasetItemProcessor {

    private static final String FORMAT_LABEL = "CSV";

    private final @NonNull DatasetItemUploadSupport uploadSupport;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Processes uploaded CSV file by buffering to temp storage and processing asynchronously.
     * This method buffers the stream synchronously (to avoid connection timeout issues),
     * validates the CSV headers, verifies the dataset exists, then processes the file
     * asynchronously with automatic cleanup.
     *
     * @param inputStream CSV file input stream from upload
     * @param datasetId Dataset ID
     * @param workspaceId Workspace UUID
     * @param userName User name
     * @param visibility Visibility setting
     * @throws BadRequestException                       if buffering fails or CSV validation fails
     * @throws jakarta.ws.rs.NotFoundException           if the dataset does not exist in the workspace
     */
    public void processUploadedCsv(InputStream inputStream, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        log.info("Processing CSV upload for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);

        Path tempFile;
        try {
            tempFile = uploadSupport.bufferToTempFile(inputStream, "csv-upload-", ".csv", FORMAT_LABEL);
        } catch (IOException e) {
            log.error("Failed to buffer CSV file to temp storage for dataset '{}'", datasetId, e);
            throw new InternalServerErrorException("Failed to process CSV file");
        }

        try {
            validateCsvHeaders(tempFile);
            uploadSupport.verifyDatasetExists(datasetId, workspaceId, visibility);
            uploadSupport.markProcessing(datasetId, workspaceId);
        } catch (Exception e) {
            uploadSupport.deleteTempFile(tempFile);
            throw e;
        }

        log.info("Starting asynchronous CSV processing for dataset '{}' on workspaceId '{}'", datasetId, workspaceId);
        uploadSupport.runAsync(
                processFileFromPath(tempFile, datasetId, workspaceId, userName, visibility),
                tempFile, datasetId, workspaceId, FORMAT_LABEL);
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

                int batchSize = uploadSupport.getBatchSize();
                int logFrequency = uploadSupport.getLogFrequency();

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
                        totalProcessed += uploadSupport.saveBatch(batch, datasetId, workspaceId, userName, visibility);
                        batch.clear();
                    }
                }

                if (!hasDataRows) {
                    throw new BadRequestException("CSV file contains no data rows");
                }

                if (!batch.isEmpty()) {
                    batchNumber++;
                    log.debug("Saving final batch '{}' for dataset '{}', batch size: '{}'",
                            batchNumber, datasetId, batch.size());
                    totalProcessed += uploadSupport.saveBatch(batch, datasetId, workspaceId, userName, visibility);
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