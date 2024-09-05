package com.comet.opik.infrastructure.instrumentation;

import com.google.inject.AbstractModule;
import com.google.inject.matcher.Matchers;

public class InstrumentationModule extends AbstractModule {

    @Override
    protected void configure() {
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(Instrument.class), new InstrumentationAspect());
    }

}
