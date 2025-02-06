package com.comet.opik.infrastructure;

import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class ConfigurationModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {
        var batchOperationsConfig = configuration(BatchOperationsConfig.class);
        var clickHouseQueryConfig = configuration(ClickHouseQueryConfig.class);

        bind(BatchOperationsConfig.class).toInstance(batchOperationsConfig);
        bind(ClickHouseQueryConfig.class).toInstance(clickHouseQueryConfig);
    }
}
