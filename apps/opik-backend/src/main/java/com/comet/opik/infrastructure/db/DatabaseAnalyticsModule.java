package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.comet.opik.infrastructure.ClickHouseLogAppenderConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.inject.Provides;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.util.Duration;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.r2dbc.v1_0.R2dbcTelemetry;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class DatabaseAnalyticsModule extends DropwizardAwareModule<OpikConfiguration> {

    public static final String READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT = "readOnlyFreeFormSqlClickHouseClient";
    public static final String CLICKHOUSE_HEALTH_CHECK_TIMEOUT = "clickhouse_health_check_timeout";

    private transient DatabaseAnalyticsFactory databaseAnalyticsFactory;
    private transient ConnectionFactory connectionFactory;
    private transient Client clickHouseClient;
    private transient Client readOnlyFreeFormSqlClickHouseClient;

    @Override
    protected void configure() {
        databaseAnalyticsFactory = configuration().getDatabaseAnalytics();
        connectionFactory = R2dbcTelemetry.create(GlobalOpenTelemetry.get())
                .wrapConnectionFactory(databaseAnalyticsFactory.build(), ConnectionFactoryOptions.builder().build());

        clickHouseClient = databaseAnalyticsFactory.buildClient();
        environment().lifecycle().manage(new Managed() {
            @Override
            public void stop() {
                clickHouseClient.close();
            }
        });

        // Read-only client used for Agent Insights freeform SQL. The v2 client connects lazily, so building it is
        // cheap and never contacts ClickHouse until a query runs; the ollieEnabled toggle gates all usage.
        readOnlyFreeFormSqlClickHouseClient = buildReadOnlyFreeFormSqlClient();
        environment().lifecycle().manage(new Managed() {
            @Override
            public void stop() {
                readOnlyFreeFormSqlClickHouseClient.close();
            }
        });

        ClickHouseLogAppenderConfig clickHouseLogAppenderConfig = configuration(ClickHouseLogAppenderConfig.class);

        // Initialize the UserFacingRuleLollingFactory
        UserFacingLoggingFactory.init(connectionFactory, clickHouseLogAppenderConfig.getBatchSize(),
                clickHouseLogAppenderConfig.getFlushIntervalDuration());
    }

    // Reuse the main analytics connection params (same ClickHouse instance) and only swap in the restricted user's
    // credentials. No queryParameters: the user runs under readonly=1 and would reject per-query server settings.
    private Client buildReadOnlyFreeFormSqlClient() {
        var main = configuration().getDatabaseAnalytics();
        var freeFormSqlConfig = configuration().getDatabaseAnalyticsReadOnlyFreeFormSql();
        var factory = new DatabaseAnalyticsFactory();
        factory.setProtocol(main.getProtocol());
        factory.setHost(main.getHost());
        factory.setPort(main.getPort());
        factory.setDatabaseName(main.getDatabaseName());
        factory.setUsername(freeFormSqlConfig.getUsername());
        factory.setPassword(freeFormSqlConfig.getPassword());
        factory.setClientSocketTimeout(freeFormSqlConfig.getSocketTimeout());
        return factory.buildClient();
    }

    @Provides
    @Singleton
    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    @Provides
    @Singleton
    public Client getClickHouseClient() {
        return clickHouseClient;
    }

    @Provides
    @Singleton
    @Named(READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT)
    public Client getReadOnlyFreeFormSqlClickHouseClient() {
        return readOnlyFreeFormSqlClickHouseClient;
    }

    @Provides
    @Singleton
    public ZeroRowsRetryPolicy getZeroRowsRetryPolicy(OpikConfiguration configuration) {
        var retry = configuration.getDatasetVersioning().zeroRowsRetry();
        return new ZeroRowsRetryPolicy(retry.maxAttempts(),
                retry.minBackoff().toJavaDuration(), retry.maxBackoff().toJavaDuration());
    }

    @Provides
    @Singleton
    @Named("Database Analytics Database Name")
    public String getDatabaseName() {
        return databaseAnalyticsFactory.getDatabaseName();
    }

    @Provides
    @Singleton
    @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT)
    public Duration getHealthCheckTimeout() {
        return databaseAnalyticsFactory.getHealthCheckTimeout();
    }

    @Provides
    @Singleton
    public TransactionTemplateAsync getTransactionTemplate(ConnectionFactory connectionFactory) {
        return new TransactionTemplateAsyncImpl(connectionFactory);
    }

}
