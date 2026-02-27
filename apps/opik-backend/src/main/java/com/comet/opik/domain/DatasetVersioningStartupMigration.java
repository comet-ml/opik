package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatasetVersioningMigrationConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Runs the dataset versioning items_total migration synchronously at application startup.
 * <p>
 * This replaces the previous Quartz-based async migration job with a blocking startup check:
 * <ul>
 *   <li>If all versions are already migrated → graceful skip (INFO log)</li>
 *   <li>If unmigrated versions exist → run migration synchronously</li>
 *   <li>If migration fails → fail-fast with descriptive error message</li>
 * </ul>
 */
@Slf4j
@Singleton
public class DatasetVersioningStartupMigration {

    private static final String SKIP_INSTRUCTIONS = "Set DATASET_VERSIONING_STARTUP_MIGRATION_ENABLED=false to skip and investigate manually. "
            + "This migration applies to dataset versions created by the Liquibase schema migration "
            + "(where dataset_id = dataset_version_id and items_total = -1).";

    private final DatasetVersioningMigrationService migrationService;
    private final TransactionTemplate template;
    private final DatasetVersioningMigrationConfig config;

    @Inject
    public DatasetVersioningStartupMigration(
            @NonNull DatasetVersioningMigrationService migrationService,
            @NonNull TransactionTemplate template,
            @NonNull @Config("datasetVersioningMigration") DatasetVersioningMigrationConfig config) {
        this.migrationService = migrationService;
        this.template = template;
        this.config = config;
    }

    /**
     * Runs or verifies the dataset versioning items_total migration.
     * <p>
     * If no unmigrated versions exist, logs and returns immediately.
     * If unmigrated versions exist, runs the migration synchronously and verifies completion.
     *
     * @throws IllegalStateException if the migration fails or unmigrated versions remain after execution
     */
    public void runOrVerify() {
        long unmigrated = countUnmigratedVersions();

        if (unmigrated == 0) {
            log.info("Dataset versioning startup migration: all dataset versions fully migrated, skipping");
            return;
        }

        log.info("Dataset versioning startup migration: found {} unmigrated dataset versions, running migration",
                unmigrated);

        try {
            int batchSize = config.getItemsTotalDatasetsBatchSize();
            Duration timeout = Duration.ofSeconds(config.getItemsTotalJobTimeoutSeconds());

            migrationService.runItemsTotalMigration(batchSize)
                    .block(timeout);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Dataset versioning startup migration: data migration failed. " + SKIP_INSTRUCTIONS, e);
        }

        long remaining = countUnmigratedVersions();
        if (remaining > 0) {
            throw new IllegalStateException(
                    "Dataset versioning startup migration: " + remaining
                            + " dataset versions still need migration after execution. " + SKIP_INSTRUCTIONS);
        }

        log.info("Dataset versioning startup migration: completed successfully, all dataset versions migrated");
    }

    private long countUnmigratedVersions() {
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.countVersionsNeedingItemsTotalMigration();
        });
    }
}
