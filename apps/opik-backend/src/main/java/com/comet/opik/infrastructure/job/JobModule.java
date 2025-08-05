package com.comet.opik.infrastructure.job;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.dropwizard.jobs.GuiceJobManager;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

@Slf4j
public class JobModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public GuiceJobManager provideGuiceJobManager(@NonNull Injector injector) {
        var guiceJobManager = new GuiceJobManager(configuration(), injector);
        environment().lifecycle().manage(guiceJobManager);
        log.info("Added GuiceJobManager to lifecycle management");
        return guiceJobManager;
    }
}
