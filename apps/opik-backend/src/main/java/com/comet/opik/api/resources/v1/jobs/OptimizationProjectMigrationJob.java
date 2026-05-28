package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.OptimizationProjectMigrationService;
import com.comet.opik.infrastructure.OptimizationProjectMigrationConfig;
import com.comet.opik.infrastructure.ProjectMigrationJobConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.domain.OptimizationProjectMigrationService.METRIC_NAMESPACE;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class OptimizationProjectMigrationJob extends AbstractProjectMigrationJob {

    private final OptimizationProjectMigrationService migrationService;
    private final OptimizationProjectMigrationConfig config;

    @Inject
    public OptimizationProjectMigrationJob(
            @NonNull OptimizationProjectMigrationService migrationService,
            @NonNull @Config("optimizationProjectMigration") OptimizationProjectMigrationConfig config,
            @NonNull LockService lockService) {
        super(lockService);
        this.migrationService = migrationService;
        this.config = config;
    }

    @Override
    protected String entityLabel() {
        return "Optimization project";
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
