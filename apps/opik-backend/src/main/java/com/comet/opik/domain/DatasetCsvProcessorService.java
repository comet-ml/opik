package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetCsvProcessorServiceImpl.class)
public interface DatasetCsvProcessorService {
    /**
     * Process a CSV file from S3/MinIO and create dataset items asynchronously
     */
    Mono<Void> processCsvAsync(UUID datasetId, String filePath, String workspaceId, String userName);
}

@Slf4j
@Singleton
class DatasetCsvProcessorServiceImpl implements DatasetCsvProcessorService {

    private static final int BATCH_SIZE = 1000;

    private final @NonNull FileService fileService;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Scheduler csvProcessingScheduler;

    @Inject
    public DatasetCsvProcessorServiceImpl(
            @NonNull FileService fileService,
            @NonNull DatasetItemDAO datasetItemDAO,
            @NonNull TransactionTemplate template,
            @NonNull IdGenerator idGenerator,
            @NonNull @Named("csvProcessingScheduler") Scheduler csvProcessingScheduler) {
        this.fileService = fileService;
        this.datasetItemDAO = datasetItemDAO;
        this.template = template;
        this.idGenerator = idGenerator;
        this.csvProcessingScheduler = csvProcessingScheduler;
    }

    @Override
    public Mono<Void> processCsvAsync(@NonNull UUID datasetId, @NonNull String filePath, @NonNull String workspaceId,
            @NonNull String userName) {
        log.info("Starting async CSV processing for dataset '{}', file '{}'", datasetId, filePath);

        return Mono.fromCallable(() -> {
            try {
                processCsvFile(datasetId, filePath, workspaceId, userName);
                return null;
            } catch (Exception exception) {
                log.error("Failed to process CSV for dataset '{}'", datasetId, exception);
                throw exception;
            }
        })
                .subscribeOn(csvProcessingScheduler)
                .then();
    }

    private void processCsvFile(UUID datasetId, String filePath, String workspaceId, String userName)
            throws IOException {
        long startTime = System.currentTimeMillis();

        try {
            // Download CSV from S3/MinIO
            log.info("Downloading CSV file from S3/MinIO: '{}'", filePath);
            var inputStream = fileService.download(filePath);

            if (inputStream == null) {
                throw new NotFoundException("CSV file not found: '%s'".formatted(filePath));
            }

            // Parse CSV and create dataset items in batches
            try (CSVReader csvReader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String[] headers = csvReader.readNext();

                if (headers == null || headers.length == 0) {
                    throw new BadRequestException("CSV file is empty or has no headers");
                }

                log.info("CSV headers found: {}", (Object) headers);

                List<DatasetItem> batch = new ArrayList<>();
                String[] row;
                int rowCount = 0;

                while ((row = csvReader.readNext()) != null) {
                    rowCount++;

                    // Convert CSV row to DatasetItem
                    Map<String, JsonNode> rowData = new HashMap<>();
                    for (int i = 0; i < headers.length && i < row.length; i++) {
                        rowData.put(headers[i], JsonUtils.readTree("\"" + row[i].replace("\"", "\\\"") + "\""));
                    }

                    var datasetItem = DatasetItem.builder()
                            .id(idGenerator.generateId())
                            .source(DatasetItemSource.MANUAL)
                            .data(rowData)
                            .build();

                    batch.add(datasetItem);

                    // Process in batches
                    if (batch.size() >= BATCH_SIZE) {
                        saveBatch(datasetId, batch, workspaceId, userName);
                        batch.clear();
                        log.info("Processed '{}' rows for dataset '{}'", rowCount, datasetId);
                    }
                }

                // Save remaining items
                if (!batch.isEmpty()) {
                    saveBatch(datasetId, batch, workspaceId, userName);
                    log.info("Processed final batch for dataset '{}', total rows: '{}'", datasetId, rowCount);
                }

                // Mark processing as complete
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    dao.completeCsvProcessing(datasetId, workspaceId, userName);
                    return null;
                });

                long duration = System.currentTimeMillis() - startTime;
                log.info("CSV processing completed for dataset '{}', processed '{}' rows in '{}'ms", datasetId,
                        rowCount, duration);

            } catch (CsvValidationException exception) {
                throw new BadRequestException("Invalid CSV format: %s".formatted(exception.getMessage()), exception);
            }

        } catch (Exception exception) {
            // Mark processing as failed
            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetDAO.class);
                dao.failCsvProcessing(datasetId, workspaceId, exception.getMessage(), userName);
                return null;
            });

            log.error("Failed to process CSV for dataset '{}'", datasetId, exception);
            throw exception;
        }
    }

    private void saveBatch(UUID datasetId, List<DatasetItem> items, String workspaceId, String userName) {
        // Save items using DatasetItemDAO
        datasetItemDAO.save(datasetId, items)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
    }
}
