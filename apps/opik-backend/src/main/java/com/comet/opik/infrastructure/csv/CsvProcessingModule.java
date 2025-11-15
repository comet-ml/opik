package com.comet.opik.infrastructure.csv;

import com.comet.opik.infrastructure.CsvProcessingConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guice module for CSV processing infrastructure.
 * Provides a dedicated Scheduler with custom thread pool for CSV processing tasks.
 */
@Slf4j
public class CsvProcessingModule extends AbstractModule {

    @Override
    protected void configure() {
        // Bind the scheduler lifecycle manager
        bind(CsvProcessingSchedulerLifecycle.class).asEagerSingleton();
    }

    @Provides
    @Singleton
    @Named("csvProcessingScheduler")
    public Scheduler provideCsvProcessingScheduler(@Config("csvProcessingConfig") @NonNull CsvProcessingConfig config) {
        log.info("Creating dedicated CSV processing scheduler with '{}' threads, queue capacity: '{}'",
                config.getThreadPoolSize(), config.getQueueCapacity());

        // Create custom thread factory with meaningful names for monitoring
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(config.getThreadNamePrefix() + counter.getAndIncrement());
                thread.setDaemon(false); // Non-daemon threads for CSV processing
                return thread;
            }
        };

        // Create bounded elastic scheduler with custom configuration
        // This provides a thread pool specifically for CSV processing tasks
        return Schedulers.newBoundedElastic(
                config.getThreadPoolSize(),
                config.getQueueCapacity(),
                threadFactory,
                60 // Time to live in seconds for idle threads
        );
    }

    /**
     * Lifecycle manager for the CSV processing scheduler.
     * Ensures proper shutdown when the application stops.
     */
    @Singleton
    static class CsvProcessingSchedulerLifecycle implements Managed {

        private final Scheduler scheduler;

        @Inject
        CsvProcessingSchedulerLifecycle(@Named("csvProcessingScheduler") Scheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void start() {
            log.info("CSV processing scheduler started");
        }

        @Override
        public void stop() {
            log.info("Shutting down CSV processing scheduler");
            scheduler.dispose();
            log.info("CSV processing scheduler shut down completed");
        }
    }
}

