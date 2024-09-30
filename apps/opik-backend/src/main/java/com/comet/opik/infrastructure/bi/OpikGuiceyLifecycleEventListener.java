package com.comet.opik.infrastructure.bi;

import com.google.inject.Injector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class OpikGuiceyLifecycleEventListener implements GuiceyLifecycleListener {

    // This event cannot depend on authentication
    private final AtomicReference<Injector> injector = new AtomicReference<>();

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {

        if (event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());
        }

        if (event.getType() == GuiceyLifecycle.ApplicationStarted) {
            var installationReportService = injector.get().getInstance(InstallationReportService.class);

            installationReportService.reportInstallation();
        }
    }

}
