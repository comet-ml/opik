package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

import static com.comet.opik.infrastructure.UsageReportConfig.ServerStatsConfig;

public class BiModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public ServerStatsConfig provideServerStatsConfig() {
        return configuration().getUsageReport().getServerStats();
    }
}
