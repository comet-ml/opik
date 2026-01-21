package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.CsvDatasetExportProcessor;
import com.comet.opik.domain.DatasetExportJobService;
import com.comet.opik.domain.DatasetExportMessage;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;

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
    private final FeatureFlags featureFlags;

    @Inject
    public DatasetExportJobSubscriber(
            @NonNull @Config("datasetExport") DatasetExportConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull DatasetExportJobService jobService,
            @NonNull CsvDatasetExportProcessor csvProcessor,
            @NonNull FeatureFlags featureFlags) {
        super(config, redisClient, DatasetExportConfig.PAYLOAD_FIELD, "opik", "dataset_export");
        this.config = config;
        this.jobService = jobService;
        this.csvProcessor = csvProcessor;
        this.featureFlags = featureFlags;
    }

    @Override
    public void start() {
        if (isDisabled()) {
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
        if (isDisabled()) {
            return;
        }

        log.info("Stopping dataset export job subscriber");
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull DatasetExportMessage message) {
        log.info("Processing dataset export job: jobId='{}', datasetId='{}', workspaceId='{}', versionId='{}'",
                message.jobId(), message.datasetId(), message.workspaceId(), message.versionId());

        // Resolve versionId based on feature flag
        // When versioning is disabled, always use null (legacy table)
        UUID resolvedVersionId = featureFlags.isDatasetVersioningEnabled() ? message.versionId() : null;

        if (!featureFlags.isDatasetVersioningEnabled() && message.versionId() != null) {
            log.info("Dataset versioning is disabled, ignoring versionId '{}' and using legacy table",
                    message.versionId());
        }

        // Set reactive context for the processing
        return jobService.updateJobToProcessing(message.jobId()) // Set status to PROCESSING first
                .then(csvProcessor.generateAndUploadCsv(message.datasetId(), resolvedVersionId))
                .flatMap(result -> {
                    log.info("CSV generated successfully for job '{}', file path: '{}', expires at: '{}'",
                            message.jobId(), result.filePath(), result.expiresAt());
                    return jobService.updateJobToCompleted(message.jobId(), result.filePath(),
                            result.expiresAt());
                })
                .then()
                .onErrorResume(throwable -> {
                    log.error("Failed to process dataset export job: jobId='{}'", message.jobId(), throwable);
                    // Use a user-friendly message - technical details are logged above
                    String errorMessage = "Failed to export dataset. Please try again later.";
                    return jobService.updateJobToFailed(message.jobId(), errorMessage)
                            .then(Mono.error(throwable)); // Re-throw to prevent ACK
                })
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER)); // System user for async processing
    }

    private boolean isDisabled() {
        if (!config.isEnabled()) {
            log.info("Dataset export job subscriber is disabled, skipping lifecycle operation");
            return true;
        }
        return false;
    }
}
