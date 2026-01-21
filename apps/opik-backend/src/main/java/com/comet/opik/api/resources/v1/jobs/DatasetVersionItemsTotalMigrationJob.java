package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.DatasetVersioningMigrationService;
import com.comet.opik.infrastructure.DatasetVersioningMigrationConfig;
import com.comet.opik.infrastructure.lock.LockService;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

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
    private final LockService lockService;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    @Inject
    public DatasetVersionItemsTotalMigrationJob(
            @NonNull DatasetVersioningMigrationService migrationService,
            @NonNull @Config("datasetVersioningMigration") DatasetVersioningMigrationConfig config,
            @NonNull LockService lockService) {
        this.migrationService = migrationService;
        this.config = config;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        // Check for interruption before starting
        if (interrupted.get()) {
            log.info("Dataset version items_total migration job interrupted before execution, skipping");
            return;
        }

        log.info("Starting dataset version items_total migration job");

        var lock = new Lock("dataset_version_migration_job",
                DatasetVersionItemsTotalMigrationJob.class.getSimpleName());
        Duration jobTimeout = Duration.ofSeconds(config.getItemsTotalJobTimeoutSeconds());
        Duration lockWaitTime = Duration.ofSeconds(5); // Short wait to check if another job instance is running
        int batchSize = config.getItemsTotalDatasetsBatchSize();

        // Use bestEffortLock to prevent duplicate job executions
        // The migration service no longer uses internal locking to avoid deadlocks
        Disposable subscription = lockService.bestEffortLock(
                lock,
                Mono.defer(() -> {
                    // Check for interruption before processing
                    if (interrupted.get()) {
                        log.info("Dataset version items_total migration interrupted before processing, skipping");
                        return Mono.empty();
                    }

                    // Execute the migration with timeout protection
                    return migrationService.runItemsTotalMigration(batchSize)
                            .timeout(jobTimeout);
                }),
                Mono.defer(() -> {
                    log.info("Could not acquire lock for dataset version items_total migration job, " +
                            "another instance is already running");
                    return Mono.empty();
                }),
                jobTimeout,
                lockWaitTime).subscribe(
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
        log.info("Dataset version items_total migration job scheduled, processing will continue asynchronously");
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
