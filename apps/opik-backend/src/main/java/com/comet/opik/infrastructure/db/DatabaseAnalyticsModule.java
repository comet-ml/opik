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

    public static final String READ_ONLY_CLICKHOUSE_CLIENT = "readOnlyClickHouseClient";

    private transient DatabaseAnalyticsFactory databaseAnalyticsFactory;
    private transient ConnectionFactory connectionFactory;
    private transient Client clickHouseClient;
    private transient Client readOnlyClickHouseClient;

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

        // Read-only client used for Agent Insights freeform SQL. It connects as a separate, restricted ClickHouse
        // user (single-statement mode, no write settings). The v2 client connects lazily, so building it is cheap and
        // never contacts ClickHouse until a query runs; the agentInsightsEnabled toggle gates all actual usage.
        readOnlyClickHouseClient = configuration().getDatabaseAnalyticsReadOnly().buildClient();
        environment().lifecycle().manage(new Managed() {
            @Override
            public void stop() {
                readOnlyClickHouseClient.close();
            }
        });

        ClickHouseLogAppenderConfig clickHouseLogAppenderConfig = configuration(ClickHouseLogAppenderConfig.class);

        // Initialize the UserFacingRuleLollingFactory
        UserFacingLoggingFactory.init(connectionFactory, clickHouseLogAppenderConfig.getBatchSize(),
                clickHouseLogAppenderConfig.getFlushIntervalDuration());
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
    @Named(READ_ONLY_CLICKHOUSE_CLIENT)
    public Client getReadOnlyClickHouseClient() {
        return readOnlyClickHouseClient;
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
    @Named("clickhouse_health_check_timeout")
    public Duration getHealthCheckTimeout() {
        return databaseAnalyticsFactory.getHealthCheckTimeout();
    }

    @Provides
    @Singleton
    public TransactionTemplateAsync getTransactionTemplate(ConnectionFactory connectionFactory) {
        return new TransactionTemplateAsyncImpl(connectionFactory);
    }

}
