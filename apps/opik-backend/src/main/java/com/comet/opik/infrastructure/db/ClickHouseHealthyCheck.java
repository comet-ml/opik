package com.comet.opik.infrastructure.db;

import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

@Singleton
public class ClickHouseHealthyCheck extends NamedHealthCheck {

    private final TransactionTemplateAsync template;
    private final java.time.Duration healthCheckTimeout;

    @Inject
    public ClickHouseHealthyCheck(@NonNull TransactionTemplateAsync template,
            @Named("clickhouse_health_check_timeout") Duration healthCheckTimeout) {
        this.template = template;
        this.healthCheckTimeout = healthCheckTimeout.toJavaDuration();
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
                    .block(healthCheckTimeout);
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }
    }
}
