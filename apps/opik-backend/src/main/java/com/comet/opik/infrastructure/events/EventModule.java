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

/**
 * This module provides a singleton instance of {@link com.google.common.eventbus.EventBus} for use throughout the application.
 * <br>
 * To add an event listener, create a bean and annotate it with {@code @EagerSingleton}. Then, annotate the event handler method(s) with {@code @Subscribe}.
 * <br>
 * <br>
 * Example:
 * <pre>{@code
 * @EagerSingleton
 * public class MyEventListener {
 *   @Subscribe
 *   public void onMyEvent(MyEvent myEvent) {
 *     // Handle the event here...
 *   }
 * }}</pre>
 * <br>
 * <br>
 * The {@link EventListenerRegistrar} will automatically register all classes following the above pattern as event listeners when the application starts up via Guice.
 *
 */
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
