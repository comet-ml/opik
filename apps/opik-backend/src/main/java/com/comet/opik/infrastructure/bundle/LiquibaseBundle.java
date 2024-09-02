package com.comet.opik.infrastructure.bundle;

import com.comet.opik.infrastructure.OpikConfiguration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.migrations.MigrationsBundle;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.function.Function;

@Builder
public class LiquibaseBundle extends MigrationsBundle<OpikConfiguration> {

    public static final String DB_APP_STATE_NAME = "db";
    public static final String DB_APP_ANALYTICS_NAME = "dbAnalytics";

    private static final String MIGRATIONS_PATTERN = "liquibase/%s/changelog.xml";
    public static final String DB_APP_STATE_MIGRATIONS_FILE_NAME = MIGRATIONS_PATTERN.formatted("db-app-state");
    public static final String DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME = MIGRATIONS_PATTERN.formatted("db-app-analytics");

    @NonNull @Accessors(fluent = true)
    @Getter
    private final String name;

    @NonNull @Getter
    private final String migrationsFileName;

    @NonNull private final Function<OpikConfiguration, DataSourceFactory> dataSourceFactoryFunction;

    @Override
    public DataSourceFactory getDataSourceFactory(OpikConfiguration configuration) {
        return dataSourceFactoryFunction.apply(configuration);
    }
}
