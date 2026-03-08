package com.comet.opik.infrastructure.db;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.time.Duration;

@Singleton
public class ClickHouseHealthyCheck extends NamedHealthCheck {

    private final TransactionTemplateAsync template;
    private final int healthCheckTimeoutSeconds;

    @Inject
    public ClickHouseHealthyCheck(@NonNull TransactionTemplateAsync template,
            @Named("ClickHouse Health Check Timeout Seconds") int healthCheckTimeoutSeconds) {
        this.template = template;
        this.healthCheckTimeoutSeconds = healthCheckTimeoutSeconds;
    }

    @Override
    public String getName() {
        return "clickhouse";
    }

    @Override
    protected Result check() {
        try {
            return template.nonTransaction(connection -> Mono.from(connection.createStatement("SELECT 1").execute())
                    .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> row.get(0))))
                    .map(o -> Result.healthy()))
                    .block(Duration.ofSeconds(healthCheckTimeoutSeconds));
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }
    }
}
