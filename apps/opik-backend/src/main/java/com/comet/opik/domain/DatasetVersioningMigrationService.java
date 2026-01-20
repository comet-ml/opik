package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.lock.LockService.Lock;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetVersioningMigrationService {

    private final @NonNull LockService lockService;
    private final @NonNull DatasetItemVersionDAO datasetItemVersionDAO;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;

    private static final Lock ITEMS_TOTAL_MIGRATION_LOCK = new Lock("dataset_version_items_total_migration");
    private static final String CURSOR_MIN = ""; // Empty string is less than any string value

    /**
     * Ensures a dataset has been migrated to the versioning system (lazy migration).
     * <p>
     * This method checks if the dataset has any versions. If not, it performs a
     * migration by creating version 1 with all existing items from the legacy
     * dataset_items table.
     * <p>
     * The migration is protected by a distributed lock to prevent concurrent
     * migrations of the same dataset.
     *
     * @param datasetId the dataset ID to ensure is migrated
     * @param workspaceId the workspace ID
     * @param userName the user performing the operation
     * @return a Mono that completes when the dataset is ensured to be migrated
     */
    public Mono<Void> ensureDatasetMigrated(@NonNull UUID datasetId, @NonNull String workspaceId,
            @NonNull String userName) {
        return Mono.defer(() -> {
            // First check if dataset has versions (fast check without lock)
            return hasVersions(datasetId, workspaceId)
                    .flatMap(hasVersions -> {
                        if (hasVersions) {
                            log.debug("Dataset '{}' already has versions, skipping lazy migration", datasetId);
                            return Mono.empty();
                        }

                        // Dataset needs migration - acquire lock and migrate
                        log.info("Dataset '{}' has no versions, performing lazy migration", datasetId);
                        Lock migrationLock = new Lock("dataset_lazy_migration_" + datasetId);

                        return lockService.executeWithLockCustomExpire(
                                migrationLock,
                                Mono.defer(() -> performLazyMigration(datasetId, workspaceId, userName)),
                                DEFAULT_LOCK_TIMEOUT);
                    });
        });
    }

    /**
     * Checks if a dataset has any versions.
     *
     * @param datasetId the dataset ID
     * @param workspaceId the workspace ID
     * @return a Mono emitting true if the dataset has versions, false otherwise
     */
    private Mono<Boolean> hasVersions(UUID datasetId, String workspaceId) {
        return Mono.fromCallable(() -> {
            long count = template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetVersionDAO.class);
                return dao.countByDatasetId(datasetId, workspaceId);
            });
            return count > 0;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Performs the actual lazy migration of a dataset.
     * <p>
     * This method double-checks if the dataset still needs migration (in case another
     * thread migrated it while waiting for the lock), then performs the migration.
     * The entire migration runs in a single transaction.
     *
     * @param datasetId the dataset ID
     * @param workspaceId the workspace ID
     * @param userName the user performing the migration
     * @return a Mono that completes when migration is done
     */
    private Mono<Void> performLazyMigration(UUID datasetId, String workspaceId, String userName) {
        return Mono.<Void>fromCallable(() -> {
            // Run the entire migration in a single WRITE transaction
            return template.inTransaction(WRITE, handle -> {
                // Double-check if dataset still needs migration
                var dao = handle.attach(DatasetVersionDAO.class);
                long count = dao.countByDatasetId(datasetId, workspaceId);

                if (count > 0) {
                    // Another thread already migrated this dataset
                    log.debug("Dataset '{}' was already migrated by another thread", datasetId);
                    return null;
                }

                log.info("Starting lazy migration for dataset '{}' in a single transaction", datasetId);

                // Execute the reactive migration chain and block within the transaction
                // This ensures all operations run in this single transaction
                migrateSingleDataset(datasetId, workspaceId, userName).block();

                log.info("Successfully completed lazy migration for dataset '{}'", datasetId);
                return null;
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Runs the items_total migration for dataset versions created by Liquibase.
     * <p>
     * This migration calculates and updates the items_total field for dataset versions
     * where dataset_id = dataset_version_id (indicating they were created by the Liquibase
     * migration) and items_total is 0.
     * <p>
     * The migration is safe to run multiple times and will continue from where it stopped.
     * It processes versions in batches to avoid memory issues and uses a distributed lock
     * to prevent concurrent executions.
     *
     * @param batchSize number of versions to process in each batch
     * @param lockTimeout timeout for the distributed lock
     * @return a Mono that completes when the migration is done
     */
    public Mono<Void> runItemsTotalMigration(int batchSize, Duration lockTimeout) {
        log.info("Starting dataset version items_total migration with batch size '{}' and lock timeout '{}'",
                batchSize, lockTimeout);

        return lockService.executeWithLockCustomExpire(
                ITEMS_TOTAL_MIGRATION_LOCK,
                Mono.defer(() -> migrateItemsTotalInBatches(batchSize, CURSOR_MIN)),
                lockTimeout);
    }

    /**
     * Processes dataset versions in batches, calculating and updating items_total.
     * <p>
     * This method continuously processes batches until no more versions need migration.
     * Uses cursor-based pagination to iterate through all versions.
     * Each batch:
     * 1. Finds versions where dataset_id = dataset_version_id and items_total = 0
     * 2. Calculates item counts from ClickHouse in a single query
     * 3. Updates MySQL with the calculated totals
     *
     * @param batchSize number of versions to process per batch
     * @param lastSeenVersionId cursor for pagination (UUID string)
     * @return a Mono that completes when all batches are processed
     */
    private Mono<Void> migrateItemsTotalInBatches(int batchSize, String lastSeenVersionId) {
        return Mono.defer(() -> {
            log.debug("Fetching next batch of '{}' dataset versions for items_total migration (cursor: '{}')",
                    batchSize, lastSeenVersionId);

            return Mono.fromCallable(() -> {
                // Find next batch of versions that need migration
                return template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetVersionDAO.class);
                    return dao.findVersionsNeedingItemsTotalMigration(lastSeenVersionId, batchSize);
                });
            })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(versions -> {
                        if (versions.isEmpty()) {
                            log.info("Dataset version items_total migration complete - no more versions to process");
                            return Mono.empty();
                        }

                        log.info("Processing batch of '{}' dataset versions for items_total calculation (cursor: '{}')",
                                versions.size(), lastSeenVersionId);

                        // Get the last version ID in this batch for the next cursor
                        String nextCursor = versions.get(versions.size() - 1).versionId().toString();

                        return processBatchItemsTotals(versions)
                                .then(Mono.defer(() -> migrateItemsTotalInBatches(batchSize, nextCursor))); // Process next batch
                    });
        });
    }

    /**
     * Processes a batch of dataset versions, calculating and updating their items_total.
     * <p>
     * This method:
     * 1. Executes a single ClickHouse query to count items for all versions
     * 2. Updates MySQL with the calculated totals in a single transaction
     *
     * @param versions list of version info to process
     * @return a Mono that completes when the batch is processed
     */
    private Mono<Void> processBatchItemsTotals(List<DatasetVersionInfo> versions) {
        log.debug("Calculating items_total for '{}' versions", versions.size());

        // Calculate item counts from ClickHouse
        return datasetItemVersionDAO.countItemsInVersionsBatch(versions)
                .collectList()
                .flatMap(itemCounts -> {
                    if (itemCounts.isEmpty()) {
                        log.info("No items found for batch of '{}' versions", versions.size());
                        return Mono.empty();
                    }

                    log.info("Updating items_total for '{}' versions in a single batch", itemCounts.size());

                    // Extract version IDs and totals for batch update
                    List<UUID> versionIds = itemCounts.stream()
                            .map(DatasetVersionItemsCount::versionId)
                            .toList();
                    List<Long> itemsTotals = itemCounts.stream()
                            .map(DatasetVersionItemsCount::count)
                            .toList();

                    // Update MySQL with calculated totals in a single batch
                    return Mono.fromRunnable(() -> {
                        template.inTransaction(WRITE, handle -> {
                            var dao = handle.attach(DatasetVersionDAO.class);
                            dao.batchUpdateItemsTotal(versionIds, itemsTotals);
                            log.debug("Batch updated '{}' versions with items_total", versionIds.size());
                            return null;
                        });
                    }).subscribeOn(Schedulers.boundedElastic()).then();
                })
                .doOnSuccess(unused -> log.info("Successfully processed batch of '{}' versions", versions.size()))
                .doOnError(error -> log.error("Failed to process batch of '{}' versions", versions.size(), error));
    }

    /**
     * Migrates a single dataset to the versioning system.
     * <p>
     * This method can be called for both full migration (during startup) and lazy migration
     * (on-demand when a dataset is accessed). It performs the following steps:
     * <ol>
     *   <li>Creates version 1 record if it doesn't exist</li>
     *   <li>Deletes any existing items from version 1 (cleanup)</li>
     *   <li>Copies items from legacy dataset_items table</li>
     *   <li>Updates the items_total count</li>
     *   <li>Creates the 'latest' tag</li>
     * </ol>
     *
     * @param datasetId the dataset ID to migrate
     * @param workspaceId the workspace ID
     * @param userName the user performing the migration
     * @return a Mono that completes when migration is done
     */
    public Mono<Void> migrateSingleDataset(UUID datasetId, String workspaceId, String userName) {
        UUID versionId = datasetId; // version 1 ID = dataset ID

        log.info("Migrating dataset '{}' in workspace '{}'", datasetId, workspaceId);

        return ensureVersion1Exists(datasetId, versionId, workspaceId, userName)
                .then(deleteItemsFromVersion1(datasetId, versionId))
                .then(copyItemsToVersion1(datasetId, versionId))
                .then(countAndUpdateItemsTotal(datasetId, versionId))
                .then(ensureLatestTagExists(datasetId, versionId, workspaceId))
                .doOnSuccess(unused -> log.info("Successfully migrated dataset '{}'", datasetId))
                .doOnError(error -> log.error("Failed to migrate dataset '{}'", datasetId, error));
    }

    private Mono<Void> ensureVersion1Exists(UUID datasetId, UUID versionId, String workspaceId, String userName) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("Ensuring version 1 exists for dataset '{}'", datasetId);

            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetVersionDAO.class);
                int rowsInserted = dao.ensureVersion1Exists(datasetId, versionId, workspaceId);

                if (rowsInserted > 0) {
                    log.info("Created version 1 for dataset '{}'", datasetId);
                } else {
                    log.debug("Version 1 already exists for dataset '{}'", datasetId);
                }
                return null;
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
                        template.inTransaction(WRITE, handle -> {
                            handle.attach(DatasetVersionDAO.class)
                                    .updateItemsTotal(versionId, count);
                            return null;
                        });
                        return null;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    private Mono<Void> ensureLatestTagExists(UUID datasetId, UUID versionId, String workspaceId) {
        return Mono.<Void>fromCallable(() -> {
            log.debug("Ensuring 'latest' tag exists for dataset '{}'", datasetId);

            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetVersionDAO.class);
                int rowsInserted = dao.ensureLatestTagExists(datasetId, versionId, workspaceId);

                if (rowsInserted > 0) {
                    log.info("Created 'latest' tag for dataset '{}'", datasetId);
                } else {
                    log.debug("'latest' tag already exists for dataset '{}'", datasetId);
                }
                return null;
            });

            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
