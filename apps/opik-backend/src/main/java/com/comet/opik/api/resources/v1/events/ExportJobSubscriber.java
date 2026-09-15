package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Visibility;
import com.comet.opik.domain.CsvExportProcessor;
import com.comet.opik.domain.ExportJobService;
import com.comet.opik.domain.ExportMessage;
import com.comet.opik.infrastructure.ExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Subscriber for dataset export jobs from Redis stream.
 * Receives export job messages and triggers CSV generation.
 */
@Slf4j
@Singleton
public class ExportJobSubscriber extends BaseRedisSubscriber<ExportMessage> {

    private final ExportConfig config;
    private final ExportJobService jobService;
    private final CsvExportProcessor csvProcessor;

    @Inject
    public ExportJobSubscriber(
            @NonNull @Config("datasetExport") ExportConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull ExportJobService jobService,
            @NonNull CsvExportProcessor csvProcessor) {
        super(config, redisClient, ExportConfig.PAYLOAD_FIELD, "opik", "dataset_export");
        this.config = config;
        this.jobService = jobService;
        this.csvProcessor = csvProcessor;
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
    protected Mono<Void> processEvent(@NonNull ExportMessage message) {
        log.info("Processing export job: jobId='{}', workspaceId='{}'", message.jobId(), message.workspaceId());

        // Set reactive context for the processing
        return jobService.updateJobToProcessing(message.jobId()) // Set status to PROCESSING first
                .then(jobService.getJob(message.jobId()))
                .flatMap(job -> csvProcessor.generateAndUploadCsv(job.params()))
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
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE)
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
