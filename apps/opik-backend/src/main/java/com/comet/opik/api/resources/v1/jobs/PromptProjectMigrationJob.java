package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.PromptProjectMigrationService;
import com.comet.opik.infrastructure.PromptProjectMigrationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;

import static com.comet.opik.domain.PromptProjectMigrationService.METRIC_NAMESPACE;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class PromptProjectMigrationJob extends AbstractProjectMigrationJob {

    private final PromptProjectMigrationService migrationService;
    private final PromptProjectMigrationConfig config;

    @Inject
    public PromptProjectMigrationJob(
            @NonNull PromptProjectMigrationService migrationService,
            @NonNull @Config("promptProjectMigration") PromptProjectMigrationConfig config,
            @NonNull LockService lockService) {
        super(lockService);
        this.migrationService = migrationService;
        this.config = config;
    }

    @Override
    protected String entityLabel() {
        return "Prompt project";
    }

    @Override
    protected String metricNamespace() {
        return METRIC_NAMESPACE;
    }

    @Override
    protected boolean isEnabled() {
        return config.enabled();
    }

    @Override
    protected Duration lockTimeout() {
        return config.lockTimeout().toJavaDuration();
    }

    @Override
    protected Duration lockWaitTime() {
        return config.lockWaitTime().toJavaDuration();
    }

    @Override
    protected Duration jobTimeout() {
        return config.jobTimeout().toJavaDuration();
    }

    @Override
    protected Mono<Void> runMigrationCycle() {
        return migrationService.runMigrationCycle();
    }
}
