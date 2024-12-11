package com.comet.opik.infrastructure;

import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class EncryptionUtilsModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {
        requestStaticInjection(EncryptionUtils.class);
    }
}
