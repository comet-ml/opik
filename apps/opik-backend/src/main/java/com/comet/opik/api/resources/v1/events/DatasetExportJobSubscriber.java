package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.CsvDatasetExportProcessor;
import com.comet.opik.domain.DatasetExportJobService;
import com.comet.opik.domain.DatasetExportMessage;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Subscriber for dataset export jobs from Redis stream.
 * Receives export job messages and triggers CSV generation.
 */
@Slf4j
@Singleton
public class DatasetExportJobSubscriber extends BaseRedisSubscriber<DatasetExportMessage> {

    private final DatasetExportConfig config;
    private final DatasetExportJobService jobService;
    private final CsvDatasetExportProcessor csvProcessor;

    @Inject
    public DatasetExportJobSubscriber(
            @NonNull @Config("datasetExport") DatasetExportConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull DatasetExportJobService jobService,
            @NonNull CsvDatasetExportProcessor csvProcessor) {
        super(config, redisClient, DatasetExportConfig.PAYLOAD_FIELD, "opik", "dataset_export");
        this.config = config;
        this.jobService = jobService;
        this.csvProcessor = csvProcessor;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Dataset export job subscriber is disabled, skipping startup");
            return;
        }

        log.info(
                "Starting dataset export job subscriber with config: streamName={}, consumerGroupName={}, batchSize={}",
                config.getStreamName(),
                config.getConsumerGroupName(),
                config.getConsumerBatchSize());

        super.start();
    }

    @Override
    public void stop() {
        if (!config.isEnabled()) {
            log.info("Dataset export job subscriber is disabled, skipping shutdown");
            return;
        }

        log.info("Stopping dataset export job subscriber");
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull DatasetExportMessage message) {
        log.info("Processing dataset export job: jobId='{}', datasetId='{}', workspaceId='{}'",
                message.jobId(), message.datasetId(), message.workspaceId());

        // Set reactive context for the processing
        return csvProcessor.generateAndUploadCsv(message.datasetId())
                .flatMap(filePath -> {
                    log.info("CSV generated successfully for job '{}', file path: '{}'", message.jobId(), filePath);
                    return jobService.updateJobToCompleted(message.jobId(), filePath);
                })
                .then()
                .onErrorResume(throwable -> {
                    log.error("Failed to process dataset export job: jobId='{}'", message.jobId(), throwable);
                    String errorMessage = truncateErrorMessage(
                            StringUtils.defaultIfBlank(throwable.getMessage(), throwable.toString()), 255);
                    return jobService.updateJobToFailed(message.jobId(), errorMessage)
                            .then(Mono.error(throwable)); // Re-throw to prevent ACK
                })
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER)); // System user for async processing
    }

    /**
     * Truncates error message to specified maximum length, appending "..." if truncated.
     *
     * @param message    the error message to truncate
     * @param maxLength  the maximum length allowed
     * @return truncated message or null if input is null
     */
    private String truncateErrorMessage(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength - 3) + "...";
    }
}
