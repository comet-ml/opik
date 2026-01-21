package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.DatasetVersioningMigrationService;
import com.comet.opik.infrastructure.DatasetVersioningMigrationConfig;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.Disposable;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Job that runs the dataset version items_total migration.
 * <p>
 * This is a one-time migration job that calculates and updates the items_total field
 * for dataset versions created by Liquibase migrations. The job:
 * <ul>
 *   <li>Runs once at application startup (with configurable delay)</li>
 *   <li>Processes versions in batches to avoid memory issues</li>
 *   <li>Uses distributed locking to prevent concurrent executions</li>
 *   <li>Can be interrupted gracefully during shutdown</li>
 *   <li>Has timeout protection to prevent hanging</li>
 * </ul>
 * <p>
 * After the migration completes successfully, disable it by setting
 * {@code datasetVersioningMigration.itemsTotalEnabled: false} in the configuration.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class DatasetVersionItemsTotalMigrationJob extends Job implements InterruptableJob {

    private final DatasetVersioningMigrationService migrationService;
    private final DatasetVersioningMigrationConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    @Inject
    public DatasetVersionItemsTotalMigrationJob(
            @NonNull DatasetVersioningMigrationService migrationService,
            @NonNull @Config("datasetVersioningMigration") DatasetVersioningMigrationConfig config) {
        this.migrationService = migrationService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        // Check for interruption before starting
        if (interrupted.get()) {
            log.info("Dataset version items_total migration job interrupted before execution, skipping");
            return;
        }

        log.info("Starting dataset version items_total migration job");

        Duration lockTimeout = Duration.ofSeconds(config.getItemsTotalLockTimeoutSeconds());
        int batchSize = config.getItemsTotalDatasetsBatchSize();

        // Execute the migration with timeout protection
        Disposable subscription = migrationService.runItemsTotalMigration(batchSize, lockTimeout)
                .timeout(Duration.ofSeconds(config.getItemsTotalJobTimeoutSeconds()))
                .subscribe(
                        unused -> {
                            // onNext - not used for Mono<Void>
                        },
                        error -> {
                            if (interrupted.get()) {
                                log.warn("Dataset version items_total migration was interrupted", error);
                            } else {
                                log.error("Dataset version items_total migration failed", error);
                            }
                            currentExecution.set(null);
                        },
                        () -> {
                            if (!interrupted.get()) {
                                log.info("Dataset version items_total migration completed successfully");
                                log.info("You can now disable this job by setting " +
                                        "datasetVersioningMigration.itemsTotalEnabled: false in the configuration");
                            } else {
                                log.info(
                                        "Dataset version items_total migration completed but was interrupted during execution");
                            }
                            currentExecution.set(null);
                        });

        currentExecution.set(subscription);

        // Wait for completion if not interrupted
        // This is necessary because Quartz jobs are synchronous
        while (!subscription.isDisposed() && !interrupted.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    interrupt();
                } catch (UnableToInterruptJobException ex) {
                    log.warn("Failed to interrupt job", ex);
                }
                break;
            }
        }
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        log.info("Interrupting dataset version items_total migration job");
        interrupted.set(true);

        Disposable execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("Dataset version items_total migration job interrupted successfully");
        }
    }
}
