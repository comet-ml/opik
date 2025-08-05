package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.v1.jobs.TraceThreadsClosingJob;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.google.inject.Injector;
import io.dropwizard.jobs.GuiceJobManager;
import io.dropwizard.jobs.JobConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class OpikGuiceyLifecycleEventListener implements GuiceyLifecycleListener {

    // This event cannot depend on authentication
    private final AtomicReference<Injector> injector = new AtomicReference<>();

    private final AtomicReference<GuiceJobManager> guiceJobManager = new AtomicReference<>();

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {

        switch (event.getType()) {
            case GuiceyLifecycle.ApplicationRun -> installJobScheduler(event);
            case GuiceyLifecycle.ApplicationStarted -> {
                reportInstallationsIfNeeded();
                setupDailyJob();
                setTraceThreadsClosingJob();
            }

            case GuiceyLifecycle.ApplicationStopped -> {
                shutdownJobManager();
            }
        }
    }

    private void reportInstallationsIfNeeded() {
        var installationReportService = injector.get().getInstance(InstallationReportService.class);

        installationReportService.reportInstallation();
    }

    private void installJobScheduler(GuiceyLifecycleEvent event) {
        if (event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());

            log.info("Installing jobs...");
            JobConfiguration configuration = injectorEvent.getConfiguration();
            var jobManager = new GuiceJobManager(configuration, injector.get());
            injectorEvent.getEnvironment().lifecycle().manage(jobManager);
            guiceJobManager.set(jobManager);
            log.info("Jobs installed.");
        }
    }

    private void setupDailyJob() {

        var usageReportConfig = injector.get().getInstance(OpikConfiguration.class).getUsageReport();

        if (!usageReportConfig.isEnabled()) {
            disableJob();
        } else {
            runReportIfNeeded();
        }
    }

    // This method sets up a job that periodically checks for trace threads that need to be closed.
    private void setTraceThreadsClosingJob() {
        TraceThreadConfig traceThreadConfig = injector.get().getInstance(OpikConfiguration.class)
                .getTraceThreadConfig();
        Duration closeTraceThreadJobInterval = traceThreadConfig.getCloseTraceThreadJobInterval().toJavaDuration();

        var jobDetail = JobBuilder.newJob(TraceThreadsClosingJob.class)
                .storeDurably()
                .build();

        var trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .startNow()
                .withSchedule(
                        org.quartz.SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInMilliseconds(closeTraceThreadJobInterval.toMillis())
                                .repeatForever())
                .build();

        try {
            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);
        } catch (SchedulerException e) {
            log.error("Failed to schedule job '{}'", jobDetail.getKey(), e);
        }
    }

    private void runReportIfNeeded() {
        JobKey key = JobKey.jobKey(DailyUsageReportJob.class.getName());

        try {
            var scheduler = getScheduler();
            var trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();
            scheduler.scheduleJob(trigger);
            log.info("Daily usage report enabled, running job during startup.");
        } catch (SchedulerException e) {
            log.error("Failed to schedule job '{}'", key, e);
        }
    }

    private void disableJob() {
        log.info("Daily usage report disabled, unregistering job.");

        var scheduler = getScheduler();

        var jobKey = new JobKey(DailyUsageReportJob.class.getName());

        try {
            if (scheduler.checkExists(jobKey)) {
                var deleted = scheduler.deleteJob(jobKey);
                log.info("Job '{}' unregistered. Deleted: {}", jobKey, deleted);
            } else {
                log.info("Job '{}' not found.", jobKey);
            }
        } catch (SchedulerException e) {
            log.error("Failed to unregister job '{}'", jobKey, e);
        }
    }

    private Scheduler getScheduler() {
        return guiceJobManager.get().getScheduler();
    }

    private void shutdownJobManager() {
        var jobManager = guiceJobManager.get();
        if (jobManager != null) {
            try {
                var scheduler = jobManager.getScheduler();

                // First, try graceful shutdown with timeout
                log.info("Attempting graceful scheduler shutdown...");
                scheduler.shutdown(true);

                // Wait up to 10 seconds for graceful shutdown
                var shutdownStart = Instant.now();
                var maxWaitTime = Duration.ofSeconds(10);

                while (!scheduler.isShutdown()
                        && Duration.between(shutdownStart, Instant.now()).compareTo(maxWaitTime) < 0) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (!scheduler.isShutdown()) {
                    log.warn("Scheduler did not shut down gracefully within {} seconds, forcing shutdown",
                            maxWaitTime.toSeconds());

                    // Force shutdown by interrupting running jobs
                    var runningJobs = scheduler.getCurrentlyExecutingJobs();
                    for (var jobExecution : runningJobs) {
                        try {
                            log.warn("Interrupting job: {}", jobExecution.getJobDetail().getKey());
                            scheduler.interrupt(jobExecution.getJobDetail().getKey());
                        } catch (Exception e) {
                            log.warn("Failed to interrupt job: {}", jobExecution.getJobDetail().getKey(), e);
                        }
                    }

                    // Force shutdown now
                    scheduler.shutdown(false);
                }

                log.info("JobManager shutdown completed");

            } catch (SchedulerException e) {
                log.warn("Error shutting down JobManager", e);
            }
        }
        
        guiceJobManager.set(null);
        log.info("Cleared GuiceJobManager instance");
    }
}
