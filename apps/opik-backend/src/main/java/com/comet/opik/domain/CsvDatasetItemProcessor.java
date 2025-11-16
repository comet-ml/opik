package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     * Validates CSV file format and structure.
     *
     * @param csvBytes CSV file content
     * @throws BadRequestException if CSV is invalid
     */
    public void validateCsv(byte[] csvBytes) {
        try (InputStreamReader reader = new InputStreamReader(
                new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8);
                CSVParser parser = createCsvParser(reader)) {

            List<String> headers = parser.getHeaderNames();
            if (headers.isEmpty()) {
                throw new BadRequestException("CSV file must contain headers");
            }

            if (!parser.iterator().hasNext()) {
                throw new BadRequestException("CSV file contains no data rows");
            }

            log.debug("CSV validation passed, headers: '{}'", headers);
        } catch (IOException e) {
            log.warn("Failed to validate CSV file", e);
            throw new BadRequestException("Failed to read CSV file: " + e.getMessage());
        }
    }

    /**
     * Processes CSV file asynchronously in batches.
     *
     * @param csvBytes CSV file content
     * @param datasetId Dataset ID
     * @param workspaceId Workspace UUID
     * @param userName User name
     * @param visibility Visibility setting
     * @return Mono that completes when processing is done
     */
    public Mono<Long> processCsvInBatches(byte[] csvBytes, UUID datasetId, String workspaceId,
            String userName, Visibility visibility) {
        log.info("Starting CSV batch processing for dataset '{}', file size: '{}' bytes", datasetId, csvBytes.length);

        // Verify dataset exists before processing
        return verifyDatasetExists(datasetId, workspaceId, visibility)
                .then(Mono.defer(() -> processFile(csvBytes, datasetId, workspaceId, userName, visibility)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> verifyDatasetExists(UUID datasetId, String workspaceId, Visibility visibility) {
        return Mono.fromCallable(() -> {
            datasetService.findById(datasetId, workspaceId, visibility);
            log.debug("Dataset '{}' verified", datasetId);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Long> processFile(byte[] csvBytes, UUID datasetId, String workspaceId, String userName,
            Visibility visibility) {
        return Mono.fromCallable(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8);
                    CSVParser parser = createCsvParser(reader)) {

                List<String> headers = parser.getHeaderNames();
                int batchSize = getBatchSize();
                int logFrequency = getLogFrequency();

                List<DatasetItem> batch = new ArrayList<>(batchSize);
                long totalProcessed = 0;
                int batchNumber = 0;
                long recordCount = 0;

                for (CSVRecord record : parser) {
                    recordCount++;

                    if (recordCount % logFrequency == 0) {
                        log.debug("Processing record '{}' for dataset '{}'", recordCount, datasetId);
                    }

                    Map<String, JsonNode> dataMap = headers.stream()
                            .collect(Collectors.toMap(
                                    header -> header,
                                    header -> {
                                        String value = record.get(header);
                                        return value != null && !value.isEmpty()
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
