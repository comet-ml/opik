package com.comet.opik.infrastructure.events;

import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.matcher.Matchers;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;

@Slf4j
public class EventModule extends AbstractModule {

    @Override
    protected void configure() {
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(Subscribe.class), new EventInterceptor());
    }

    @Provides
    @Singleton
    public EventBus eventBus() {
        return getEventBus();
    }

    protected EventBus getEventBus() {
        return new AsyncEventBus("opik-event-bus", Executors.newVirtualThreadPerTaskExecutor());
    }

}
