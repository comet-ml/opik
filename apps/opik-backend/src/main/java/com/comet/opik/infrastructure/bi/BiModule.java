package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UsageReportConfig;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class BiModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public UsageReportConfig provideUsageReportConfig() {
        return configuration().getUsageReport();
    }
}
