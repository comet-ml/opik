package com.comet.opik.infrastructure.db;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MysqlHealthyCheck extends NamedHealthCheck {

    private final @NonNull Jdbi jdbi;

    @Override
    public String getName() {
        return "mysql";
    }

    @Override
    protected Result check() {
        try {
            return jdbi.withHandle(handle -> {
                handle.execute("SELECT 1");
                return Result.healthy();
            });
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }
    }
}
