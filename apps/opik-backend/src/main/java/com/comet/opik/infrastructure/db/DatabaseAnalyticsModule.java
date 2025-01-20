package com.comet.opik.infrastructure.db;

import com.comet.opik.infrastructure.ClickHouseLogAppenderConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.inject.Provides;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.r2dbc.v1_0.R2dbcTelemetry;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class DatabaseAnalyticsModule extends DropwizardAwareModule<OpikConfiguration> {

    private transient DatabaseAnalyticsFactory databaseAnalyticsFactory;
    private transient ConnectionFactory connectionFactory;

    @Override
    protected void configure() {
        databaseAnalyticsFactory = configuration(DatabaseAnalyticsFactory.class);
        connectionFactory = R2dbcTelemetry.create(GlobalOpenTelemetry.get())
                .wrapConnectionFactory(databaseAnalyticsFactory.build(), ConnectionFactoryOptions.builder().build());

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
    @Named("Database Analytics Database Name")
    public String getDatabaseName() {
        return databaseAnalyticsFactory.getDatabaseName();
    }

    @Provides
    @Singleton
    public TransactionTemplateAsync getTransactionTemplate(ConnectionFactory connectionFactory) {
        return new TransactionTemplateAsyncImpl(connectionFactory);
    }

}
