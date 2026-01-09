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
 * Listener for dataset export jobs from Redis stream.
 * Receives export job messages and triggers CSV generation (to be implemented in PR#5).
 */
@Slf4j
@Singleton
public class DatasetExportJobListener extends BaseRedisSubscriber<DatasetExportMessage> {

    @Inject
    public DatasetExportJobListener(
            @NonNull @Config DatasetExportConfig config,
            @NonNull RedissonReactiveClient redisClient) {
        super(config, redisClient, DatasetExportConfig.PAYLOAD_FIELD, "opik", "dataset_export");
    }

    @Override
    protected Mono<Void> processEvent(@NonNull DatasetExportMessage message) {
        log.info("Received dataset export job: jobId='{}', datasetId='{}', workspaceId='{}'",
                message.jobId(), message.datasetId(), message.workspaceId());

        // TODO: PR#5 - Implement CSV generation logic
        // 1. Fetch dataset items from DatasetItemService
        // 2. Discover columns using DatasetItemDAO.SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID
        // 3. Generate CSV using Apache Commons CSV
        // 4. Upload to S3/MinIO using FileService.uploadStream()
        // 5. Update job status to COMPLETED using DatasetExportJobService.updateJobStatus()

        log.debug("Dataset export job processing not yet implemented (PR#5)");
        return Mono.empty();
    }
}
