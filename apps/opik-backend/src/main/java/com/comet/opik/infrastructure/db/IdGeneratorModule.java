package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class IdGeneratorModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public IdGenerator getIdGenerator() {
        return new IdGeneratorImpl();
    }

}
