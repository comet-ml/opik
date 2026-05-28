package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.DatasetProjectMigrationService;
import com.comet.opik.infrastructure.DatasetProjectMigrationConfig;
import com.comet.opik.infrastructure.ProjectMigrationJobConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.domain.DatasetProjectMigrationService.METRIC_NAMESPACE;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class DatasetProjectMigrationJob extends AbstractProjectMigrationJob {

    private final DatasetProjectMigrationService migrationService;
    private final DatasetProjectMigrationConfig config;

    @Inject
    public DatasetProjectMigrationJob(
            @NonNull DatasetProjectMigrationService migrationService,
            @NonNull @Config("datasetProjectMigration") DatasetProjectMigrationConfig config,
            @NonNull LockService lockService) {
        super(lockService);
        this.migrationService = migrationService;
        this.config = config;
    }

    @Override
    protected String entityLabel() {
        return "Dataset project";
    }

    @Override
    protected String metricNamespace() {
        return METRIC_NAMESPACE;
    }

    @Override
    protected ProjectMigrationJobConfig config() {
        return config;
    }

    @Override
    protected Mono<Void> runMigrationCycle() {
        return migrationService.runMigrationCycle();
    }
}
