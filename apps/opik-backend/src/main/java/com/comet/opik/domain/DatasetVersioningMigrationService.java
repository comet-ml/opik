package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.lock.LockService.Lock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetVersioningMigrationService {

    private final @NonNull LockService lockService;
    private final @NonNull DatasetVersionDAO datasetVersionDAO;
    private final @NonNull DatasetItemVersionDAO datasetItemVersionDAO;
    private final @NonNull org.jdbi.v3.core.Jdbi jdbi;
    private final @NonNull RequestContext requestContext;

    private static final Lock MIGRATION_LOCK = new Lock("dataset_versioning_migration");
    private static final UUID UUID_MIN = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public Mono<Void> runMigration(int batchSize, Duration lockTimeout) {
        log.info("Starting dataset versioning migration with batch size '{}' and lock timeout '{}'", batchSize,
                lockTimeout);

        return lockService.executeWithLockCustomExpire(
                MIGRATION_LOCK,
                Mono.defer(() -> migrateAllDatasets(batchSize, UUID_MIN)),
                lockTimeout);
    }

    private Mono<Void> migrateAllDatasets(int batchSize, UUID lastSeenDatasetId) {
        return Mono.defer(() -> {
            log.debug("Fetching batch of unmigrated datasets (cursor: '{}')", lastSeenDatasetId);

            // Cursor-based pagination using UUIDv7 ordering
            return datasetItemVersionDAO.findDatasetsWithHashMismatch(batchSize, lastSeenDatasetId)
                    .collectList()
                    .flatMap(datasetIds -> {
                        if (datasetIds.isEmpty()) {
                            log.info("Dataset versioning migration complete - no more datasets to migrate");
                            return Mono.empty();
                        }

                        log.info("Migrating batch of '{}' datasets (cursor: '{}')",
                                datasetIds.size(), lastSeenDatasetId);

                        UUID nextCursor = datasetIds.get(datasetIds.size() - 1); // Last ID in batch

                        return Flux.fromIterable(datasetIds)
                                .flatMap(this::migrateDataset, 1) // Sequential processing
                                .then()
                                .then(Mono.defer(() -> migrateAllDatasets(batchSize, nextCursor))); // Next batch
                    });
        });
    }

    private Mono<Void> migrateDataset(UUID datasetId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            UUID versionId = datasetId; // version 1 ID = dataset ID

            log.info("Migrating dataset '{}' in workspace '{}'", datasetId, workspaceId);

            return ensureVersion1Exists(datasetId, versionId, workspaceId, userName)
                    .then(deleteItemsFromVersion1(datasetId, versionId))
                    .then(copyItemsToVersion1(datasetId, versionId))
                    .then(countAndUpdateItemsTotal(datasetId, versionId))
                    .then(ensureLatestTagExists(datasetId, versionId, workspaceId))
                    .doOnSuccess(unused -> log.info("Successfully migrated dataset '{}'", datasetId))
                    .doOnError(error -> log.error("Failed to migrate dataset '{}'", datasetId, error));
        });
    }

    private Mono<Void> ensureVersion1Exists(UUID datasetId, UUID versionId, String workspaceId, String userName) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("Ensuring version 1 exists for dataset '{}'", datasetId);

            jdbi.useTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
                var dao = handle.attach(DatasetVersionDAO.class);
                int rowsInserted = dao.ensureVersion1Exists(datasetId, versionId, workspaceId);

                if (rowsInserted > 0) {
                    log.info("Created version 1 for dataset '{}'", datasetId);
                } else {
                    log.debug("Version 1 already exists for dataset '{}'", datasetId);
                }
            });

            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> deleteItemsFromVersion1(UUID datasetId, UUID versionId) {
        log.debug("Deleting items from version 1 for dataset '{}'", datasetId);

        return datasetItemVersionDAO.deleteItemsFromVersion(datasetId, versionId)
                .doOnSuccess(deletedCount -> {
                    if (deletedCount > 0) {
                        log.info("Deleted '{}' items from version 1 for dataset '{}'", deletedCount, datasetId);
                    } else {
                        log.debug("No items to delete from version 1 for dataset '{}'", datasetId);
                    }
                })
                .then();
    }

    private Mono<Void> copyItemsToVersion1(UUID datasetId, UUID versionId) {
        log.debug("Copying items to version 1 for dataset '{}'", datasetId);

        return datasetItemVersionDAO.copyItemsFromLegacy(datasetId, versionId)
                .doOnSuccess(copiedCount -> {
                    if (copiedCount > 0) {
                        log.info("Copied '{}' items to version 1 for dataset '{}'", copiedCount, datasetId);
                    } else {
                        log.debug("No items to copy to version 1 for dataset '{}'", datasetId);
                    }
                })
                .then();
    }

    private Mono<Void> countAndUpdateItemsTotal(UUID datasetId, UUID versionId) {
        log.debug("Counting and updating items_total for dataset '{}'", datasetId);

        return datasetItemVersionDAO.countItemsInVersion(datasetId, versionId)
                .flatMap(count -> {
                    log.debug("Dataset '{}' has '{}' items in version 1", datasetId, count);

                    return Mono.<Void>fromCallable(() -> {
                        jdbi.useTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
                            handle.attach(DatasetVersionDAO.class)
                                    .updateItemsTotal(versionId, count);
                        });
                        return null;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    private Mono<Void> ensureLatestTagExists(UUID datasetId, UUID versionId, String workspaceId) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("Ensuring 'latest' tag exists for dataset '{}'", datasetId);

            jdbi.useTransaction(TransactionIsolationLevel.READ_COMMITTED, handle -> {
                var dao = handle.attach(DatasetVersionDAO.class);
                int rowsInserted = dao.ensureLatestTagExists(datasetId, versionId, workspaceId);

                if (rowsInserted > 0) {
                    log.info("Created 'latest' tag for dataset '{}'", datasetId);
                } else {
                    log.debug("'latest' tag already exists for dataset '{}'", datasetId);
                }
            });

            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
