package com.comet.opik.infrastructure.events;

import com.comet.opik.infrastructure.GuiceBindings;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Injector;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Automatically registers all event listeners with the EventBus.
 * This class uses Guicey lifecycle events to find and register all classes
 * that have methods annotated with @Subscribe.
 *
 * This approach allows event listeners to be completely decoupled from the
 * registration process - they don't need to know how to register themselves.
 */
@Slf4j
public class EventListenerRegistrar implements GuiceyLifecycleListener {

    private final AtomicReference<Injector> injector = new AtomicReference<>();

    record ListenerInfo(Object listener, Class<?> listenerClass) {
    }

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {
        if (event.getType() == GuiceyLifecycle.ApplicationRun && event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());
            registerAllEventListeners();
        }
    }

    private void registerAllEventListeners() {
        if (injector.get() == null) {
            log.error("Injector not available, cannot register event listeners");
            throw new IllegalStateException("Injector not available");
        }

        log.info("Registering event listeners...");

        EventBus eventBus = injector.get().getInstance(EventBus.class);

        // Find all beans that have @Subscribe methods
        Set<ListenerInfo> eventListeners = findAllEventListeners();

        // Register each listener with the EventBus
        for (ListenerInfo info : eventListeners) {
            log.info("Registering event listener: {}", info.listenerClass().getName());
            eventBus.register(info.listener());
        }

        log.info("Registered {} event listeners", eventListeners.size());
    }

    private Set<ListenerInfo> findAllEventListeners() {
        return GuiceBindings.boundRawTypes(injector.get())
                .filter(this::isAnEventListener)
                .map(rawType -> {
                    try {
                        return new ListenerInfo(injector.get().getInstance(rawType), rawType);
                    } catch (Exception e) {
                        log.error("Failed to get instance for {}: {}", rawType, e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean isAnEventListener(Class<?> clazz) {
        return clazz.isAnnotationPresent(EagerSingleton.class) && hasSubscribeMethod(clazz);
    }

    private boolean hasSubscribeMethod(Class<?> clazz) {
        return Arrays.stream(clazz.getMethods())
                .anyMatch(method -> method.isAnnotationPresent(Subscribe.class));
    }
}