package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for generating CSV files from dataset items and uploading them to S3/MinIO.
 */
@ImplementedBy(CsvDatasetExportProcessorImpl.class)
public interface CsvDatasetExportProcessor {

    /**
     * Generates a CSV file from dataset items and uploads it to S3/MinIO.
     *
     * @param datasetId The dataset ID to export
     * @return A Mono containing the S3/MinIO key of the uploaded file
     */
    Mono<String> generateAndUploadCsv(UUID datasetId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CsvDatasetExportProcessorImpl implements CsvDatasetExportProcessor {

    private final @NonNull DatasetItemDAO datasetItemDao;
    private final @NonNull FileService fileService;
    private final @NonNull DatasetExportConfig exportConfig;

    private static final int BATCH_SIZE = 1000;
    private static final String CSV_CONTENT_TYPE = "text/csv";

    @Override
    public Mono<String> generateAndUploadCsv(@NonNull UUID datasetId) {
        log.info("Starting CSV generation for dataset: '{}'", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Step 1: Discover all columns in the dataset
            return discoverColumns(datasetId, workspaceId)
                    .flatMap(columns -> {
                        log.info("Discovered {} columns for dataset '{}'", columns.size(), datasetId);

                        // Step 2: Stream dataset items and generate CSV
                        return generateCsv(datasetId, columns)
                                .flatMap(csvData -> {
                                    // Step 3: Upload CSV to S3/MinIO
                                    String filePath = generateFilePath(workspaceId, datasetId);
                                    log.info("Uploading CSV to S3/MinIO: '{}'", filePath);

                                    return Mono.fromCallable(() -> {
                                        fileService.upload(filePath, csvData, CSV_CONTENT_TYPE);
                                        return filePath;
                                    });
                                });
                    });
        });
    }

    /**
     * Discovers all unique column names from the dataset items.
     *
     * @param datasetId The dataset ID
     * @param workspaceId The workspace ID
     * @return A Mono containing an ordered set of column names
     */
    private Mono<Set<String>> discoverColumns(@NonNull UUID datasetId, @NonNull String workspaceId) {
        log.debug("Discovering columns for dataset: '{}'", datasetId);

        return datasetItemDao.getColumns(datasetId)
                .map(columnsMap -> {
                    // Extract column names from the map and maintain order
                    Set<String> columnNames = new LinkedHashSet<>(columnsMap.keySet());
                    log.debug("Found columns: {}", columnNames);
                    return columnNames;
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId));
    }

    /**
     * Generates CSV data from dataset items.
     *
     * @param datasetId The dataset ID
     * @param columns The ordered set of column names
     * @return A Mono containing the CSV data as a byte array
     */
    private Mono<byte[]> generateCsv(@NonNull UUID datasetId, @NonNull Set<String> columns) {
        log.debug("Generating CSV for dataset '{}' with {} columns", datasetId, columns.size());

        return Mono.fromCallable(() -> {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader(columns.toArray(new String[0]))
                    .build();
            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat);

                // Convert set to list for indexed access
                List<String> columnList = new ArrayList<>(columns);

                // Stream dataset items in batches
                UUID lastRetrievedId = null;
                boolean hasMore = true;

                while (hasMore) {
                    UUID currentLastId = lastRetrievedId;
                    List<DatasetItem> items = datasetItemDao.getItems(datasetId, BATCH_SIZE, currentLastId)
                            .collectList()
                            .block(); // Block within callable - running on subscribeOn scheduler

                    if (items == null || items.isEmpty()) {
                        hasMore = false;
                        continue;
                    }

                    log.debug("Processing batch of {} items for dataset '{}'", items.size(), datasetId);

                    for (DatasetItem item : items) {
                        List<String> row = new ArrayList<>(columns.size());
                        Map<String, JsonNode> data = item.data();

                        for (String column : columnList) {
                            JsonNode value = data.get(column);
                            if (value == null || value.isNull()) {
                                row.add("");
                            } else if (value.isTextual()) {
                                row.add(value.asText());
                            } else {
                                row.add(value.toString());
                            }
                        }

                        csvPrinter.printRecord(row);
                    }

                    // Update lastRetrievedId for next batch
                    lastRetrievedId = items.get(items.size() - 1).id();

                    // Check if we've reached the end
                    hasMore = items.size() == BATCH_SIZE;
                }

                csvPrinter.flush();
                csvPrinter.close();
                log.info("CSV generation completed for dataset '{}', size: {} bytes", datasetId, outputStream.size());
                return outputStream.toByteArray();

            } catch (IOException e) {
                log.error("Failed to generate CSV for dataset '{}'", datasetId, e);
                throw new RuntimeException("Failed to generate CSV", e);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * Generates the S3/MinIO file path for the CSV export.
     *
     * @param workspaceId The workspace ID
     * @param datasetId The dataset ID
     * @return The S3/MinIO key
     */
    private String generateFilePath(@NonNull String workspaceId, @NonNull UUID datasetId) {
        String timestamp = Instant.now().toString().replace(":", "-");
        return String.format("exports/%s/datasets/%s/export_%s.csv", workspaceId, datasetId, timestamp);
    }
}
