package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.v1.jobs.TraceThreadsClosingJob;
import com.comet.opik.domain.alerts.MetricsAlertJob;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.google.inject.Injector;
import io.dropwizard.jobs.GuiceJobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.time.Duration;
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
                setMetricsAlertJob();
            }

            case GuiceyLifecycle.ApplicationShutdown -> shutdownJobManagerScheduler();
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
            guiceJobManager.set(injector.get().getInstance(GuiceJobManager.class));
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

        if (!traceThreadConfig.isEnabled()) {
            log.info("Trace thread closing job is disabled, skipping job setup");
            return;
        }

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
            log.info("Trace thread closing job scheduled successfully");
        } catch (SchedulerException e) {
            log.error("Failed to schedule job '{}'", jobDetail.getKey(), e);
        }
    }

    // This method sets up a job that periodically evaluates metrics-based alerts for cost and latency thresholds.
    private void setMetricsAlertJob() {
        try {
            var webhookConfig = injector.get().getInstance(OpikConfiguration.class).getWebhook();

            if (webhookConfig == null || webhookConfig.getMetrics() == null) {
                log.warn("Webhook metrics configuration not found, skipping metrics alert job setup");
                return;
            }

            Duration initialDelay = webhookConfig.getMetrics().getInitialDelay().toJavaDuration();
            Duration fixedDelay = webhookConfig.getMetrics().getFixedDelay().toJavaDuration();

            var jobDetail = JobBuilder.newJob(MetricsAlertJob.class)
                    .storeDurably()
                    .build();

            var trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .startAt(java.util.Date.from(java.time.Instant.now().plus(initialDelay)))
                    .withSchedule(
                            org.quartz.SimpleScheduleBuilder.simpleSchedule()
                                    .withIntervalInMilliseconds(fixedDelay.toMillis())
                                    .repeatForever())
                    .build();

            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);
            log.info("Metrics alert job scheduled successfully with initial delay of '{}' and fixed delay of '{}'",
                    initialDelay, fixedDelay);
        } catch (SchedulerException e) {
            log.error("Failed to schedule metrics alert job", e);
        } catch (Exception e) {
            log.error("Unexpected error setting up metrics alert job", e);
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

    private void shutdownJobManagerScheduler() {
        var jobManager = guiceJobManager.get();
        if (jobManager == null) {
            log.info("GuiceJobManager instance already cleared, nothing to shutdown");
            return;
        }
        var scheduler = jobManager.getScheduler();
        try {
            log.info("Attempting to delete all jobs from the scheduler...");
            scheduler.deleteJobs(scheduler.getJobKeys(GroupMatcher.anyGroup()).stream().toList());
            log.info("Jobs deleted");
        } catch (SchedulerException exception) {
            log.warn("Error deleting jobs during scheduler shutdown", exception);
        }
        try {
            log.info("Attempting scheduler shutdown...");
            scheduler.shutdown(false); // Don't wait for jobs to complete
            log.info("Scheduler shutdown completed");
        } catch (SchedulerException exception) {
            log.warn("Error shutting down scheduler", exception);
        }
        guiceJobManager.set(null);
        log.info("Cleared GuiceJobManager instance");
    }
}
