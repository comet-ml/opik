package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.DatasetExportMessage;
import com.comet.opik.infrastructure.DatasetExportConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Subscriber for dataset export jobs from Redis stream.
 * Receives export job messages and triggers CSV generation (to be implemented in PR#5).
 */
@Slf4j
@Singleton
public class DatasetExportJobSubscriber extends BaseRedisSubscriber<DatasetExportMessage> {

    private final DatasetExportConfig config;

    @Inject
    public DatasetExportJobSubscriber(
            @NonNull @Config DatasetExportConfig config,
            @NonNull RedissonReactiveClient redisClient) {
        super(config, redisClient, DatasetExportConfig.PAYLOAD_FIELD, "opik", "dataset_export");
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Dataset export job subscriber is disabled, skipping startup");
            return;
        }

        log.info("Starting dataset export job subscriber with config: streamName={}, consumerGroupName={}, batchSize={}",
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
        log.warn("Dataset export job processing invoked but CSV export flow is not implemented yet. " +
                "Failing job: jobId='{}', datasetId='{}', workspaceId='{}'",
                message.jobId(), message.datasetId(), message.workspaceId());

        // Return error to prevent message ACK until CSV generation is implemented in PR#5
        // TODO: PR#5 - Implement CSV generation logic
        // 1. Fetch dataset items from DatasetItemService
        // 2. Discover columns using DatasetItemDAO.SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID
        // 3. Generate CSV using Apache Commons CSV
        // 4. Upload to S3/MinIO using FileService.uploadStream()
        // 5. Update job status to COMPLETED using DatasetExportJobService.updateJobStatus()

        return Mono.error(new IllegalStateException("Dataset export job processing is not implemented yet (PR#5)"));
    }
}
