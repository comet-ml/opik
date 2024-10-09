package com.comet.opik.infrastructure.db;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.time.Duration;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ClickHouseHealthyCheck extends NamedHealthCheck {

    private final @NonNull TransactionTemplateAsync template;

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
                    .block(Duration.ofSeconds(1));
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }
    }
}
