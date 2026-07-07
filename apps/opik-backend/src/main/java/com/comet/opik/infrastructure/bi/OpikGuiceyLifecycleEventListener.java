package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.v1.jobs.AgentInsightsReportJob;
import com.comet.opik.api.resources.v1.jobs.AlertProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.AutomationRuleProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.DatasetProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.DatasetVersionItemsTotalMigrationJob;
import com.comet.opik.api.resources.v1.jobs.ExperimentDenormalizationJob;
import com.comet.opik.api.resources.v1.jobs.ExperimentProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.LocalRunnerReaperJob;
import com.comet.opik.api.resources.v1.jobs.MetricsAlertJob;
import com.comet.opik.api.resources.v1.jobs.OptimizationProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.ProjectLastUpdatedFlushJob;
import com.comet.opik.api.resources.v1.jobs.PromptProjectMigrationJob;
import com.comet.opik.api.resources.v1.jobs.RetentionCatchUpJob;
import com.comet.opik.api.resources.v1.jobs.RetentionEstimationJob;
import com.comet.opik.api.resources.v1.jobs.RetentionSlidingWindowJob;
import com.comet.opik.api.resources.v1.jobs.StreamConsumerReaperJob;
import com.comet.opik.api.resources.v1.jobs.TraceThreadsClosingJob;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.StreamConsumerReaperConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryRefreshJob;
import com.google.inject.Injector;
import io.dropwizard.jobs.GuiceJobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
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
                setAgentInsightsReportJob();
                setExperimentDenormalizationJob();
                setProjectLastUpdatedFlushJob();
                setLocalRunnerReaperJob();
                setStreamConsumerReaperJob();
                setRetentionJobs();
                setLlmModelRegistryRefreshJob();
                scheduleDatasetVersionItemsTotalMigrationJobIfEnabled();
                scheduleExperimentProjectMigrationJobIfEnabled();
                scheduleDatasetProjectMigrationJobIfEnabled();
                scheduleOptimizationProjectMigrationJobIfEnabled();
                schedulePromptProjectMigrationJobIfEnabled();
                scheduleAutomationRuleProjectMigrationJobIfEnabled();
                scheduleAlertProjectMigrationJobIfEnabled();
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

    private void setTraceThreadsClosingJob() {
        TraceThreadConfig traceThreadConfig = injector.get().getInstance(OpikConfiguration.class)
                .getTraceThreadConfig();

        if (!traceThreadConfig.isEnabled()) {
            log.info("Trace thread closing job is disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(TraceThreadsClosingJob.class,
                traceThreadConfig.getCloseTraceThreadJobInterval().toJavaDuration(), null);
    }

    private void setExperimentDenormalizationJob() {
        ExperimentDenormalizationConfig denormConfig = injector.get().getInstance(OpikConfiguration.class)
                .getExperimentDenormalization();

        if (!denormConfig.isEnabled()) {
            log.info("Experiment denormalization job is disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(ExperimentDenormalizationJob.class,
                denormConfig.getJobInterval().toJavaDuration(), null);
    }

    private void setMetricsAlertJob() {
        var webhookConfig = injector.get().getInstance(OpikConfiguration.class).getWebhook();

        if (webhookConfig == null || webhookConfig.getMetrics() == null) {
            log.warn("Webhook metrics configuration not found, skipping metrics alert job setup");
            return;
        }

        scheduleRepeatingJob(MetricsAlertJob.class,
                webhookConfig.getMetrics().getFixedDelay().toJavaDuration(),
                webhookConfig.getMetrics().getInitialDelay().toJavaDuration());
    }

    private void setProjectLastUpdatedFlushJob() {
        ProjectLastUpdatedFlushConfig flushConfig = injector.get().getInstance(OpikConfiguration.class)
                .getProjectLastUpdatedFlush();

        if (!flushConfig.isEnabled() || !flushConfig.isJobEnabled()) {
            log.info("Project last-updated flush job is disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(ProjectLastUpdatedFlushJob.class,
                flushConfig.getJobInterval().toJavaDuration(), null);
    }

    private void setLocalRunnerReaperJob() {
        LocalRunnerConfig localRunnerConfig = injector.get().getInstance(OpikConfiguration.class).getLocalRunner();

        scheduleRepeatingJob(LocalRunnerReaperJob.class,
                localRunnerConfig.getReaperJobInterval().toJavaDuration(), null);
    }

    private void setStreamConsumerReaperJob() {
        StreamConsumerReaperConfig reaperConfig = injector.get().getInstance(OpikConfiguration.class)
                .getStreamConsumerReaper();

        if (!reaperConfig.enabled()) {
            log.info("Stream consumer reaper job is disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(StreamConsumerReaperJob.class,
                reaperConfig.jobInterval().toJavaDuration(),
                reaperConfig.startupDelay().toJavaDuration());
    }

    private void setRetentionJobs() {
        RetentionConfig retentionConfig = injector.get().getInstance(OpikConfiguration.class).getRetention();

        if (!retentionConfig.isEnabled()) {
            log.info("Retention jobs are disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(RetentionSlidingWindowJob.class, retentionConfig.getInterval(), null);

        if (retentionConfig.getCatchUp().isEnabled()) {
            scheduleRepeatingJob(RetentionEstimationJob.class,
                    Duration.ofMinutes(retentionConfig.getCatchUp().getEstimationIntervalMinutes()), null);
            scheduleRepeatingJob(RetentionCatchUpJob.class,
                    retentionConfig.getCatchUp().getCatchUpInterval(), null);
        } else {
            log.info("Retention catch-up jobs are disabled, skipping estimation and catch-up job setup");
        }
    }

    private void setAgentInsightsReportJob() {
        var serviceToggles = injector.get().getInstance(OpikConfiguration.class).getServiceToggles();

        if (!serviceToggles.isAgentInsightsEnabled()) {
            log.info("Agent Insights is disabled, skipping report job setup");
            return;
        }

        var reportConfig = injector.get().getInstance(OpikConfiguration.class).getAgentInsightsReport();
        scheduleCronJob(AgentInsightsReportJob.class, reportConfig.getSchedule());
    }

    private void scheduleCronJob(Class<? extends org.quartz.Job> jobClass, String cronExpression) {
        var jobDetail = JobBuilder.newJob(jobClass)
                .storeDurably()
                .build();

        var trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(java.time.ZoneOffset.UTC)))
                .build();

        try {
            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);
            log.info("'{}' scheduled successfully with cron '{}'", jobClass.getSimpleName(), cronExpression);
        } catch (SchedulerException e) {
            log.error("Failed to schedule '{}'", jobClass.getSimpleName(), e);
        }
    }

    private void scheduleRepeatingJob(Class<? extends org.quartz.Job> jobClass, Duration interval,
            Duration initialDelay) {
        var jobDetail = JobBuilder.newJob(jobClass)
                .storeDurably()
                .build();

        var triggerBuilder = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withSchedule(
                        org.quartz.SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInMilliseconds(interval.toMillis())
                                .repeatForever());

        if (initialDelay != null && !initialDelay.isZero()) {
            triggerBuilder.startAt(java.util.Date.from(java.time.Instant.now().plus(initialDelay)));
        } else {
            triggerBuilder.startNow();
        }

        try {
            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(triggerBuilder.build());
            log.info("'{}' scheduled successfully with interval '{}'", jobClass.getSimpleName(), interval);
        } catch (SchedulerException e) {
            log.error("Failed to schedule '{}'", jobClass.getSimpleName(), e);
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

    private void setLlmModelRegistryRefreshJob() {
        LlmModelRegistryConfig registryConfig = injector.get().getInstance(OpikConfiguration.class)
                .getLlmModelRegistry();

        if (!registryConfig.isRemoteEnabled()) {
            log.info("LLM model registry remote refresh is disabled, skipping job setup");
            return;
        }

        scheduleRepeatingJob(LlmModelRegistryRefreshJob.class,
                Duration.ofSeconds(registryConfig.getRefreshIntervalSeconds()), null);
    }

    /**
     * Schedules the dataset version items_total migration job if enabled.
     * <p>
     * This is a one-time migration job that runs after application startup with a configurable delay.
     * The job calculates and updates the items_total field for dataset versions created by
     * Liquibase migrations. After successful completion, disable the job by setting
     * {@code datasetVersioningMigration.itemsTotalEnabled: false} in the configuration.
     */
    private void scheduleDatasetVersionItemsTotalMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getDatasetVersioningMigration();

        if (config == null || !config.isItemsTotalEnabled()) {
            log.info("Dataset version items_total migration job is disabled");
            return;
        }

        try {
            Duration startupDelay = Duration.ofSeconds(config.getItemsTotalStartupDelaySeconds());

            var jobDetail = JobBuilder.newJob(DatasetVersionItemsTotalMigrationJob.class)
                    .storeDurably()
                    .build();

            // Schedule job to run once after startup delay
            var trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .startAt(java.util.Date.from(java.time.Instant.now().plus(startupDelay)))
                    .build();

            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);

            log.info("Dataset version items_total migration job scheduled successfully with startup delay of '{}'",
                    startupDelay);
            log.info("The job will run once. After successful completion, disable it by setting " +
                    "datasetVersioningMigration.itemsTotalEnabled: false");
        } catch (SchedulerException e) {
            log.error("Failed to schedule dataset version items_total migration job", e);
        } catch (Exception e) {
            log.error("Unexpected error setting up dataset version items_total migration job", e);
        }
    }

    private void scheduleExperimentProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getExperimentProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Experiment project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(ExperimentProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }

    private void scheduleDatasetProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getDatasetProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Dataset project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(DatasetProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }

    private void scheduleOptimizationProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getOptimizationProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Optimization project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(OptimizationProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }

    private void schedulePromptProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getPromptProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Prompt project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(PromptProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }

    private void scheduleAutomationRuleProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getAutomationRuleProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Automation rule project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(AutomationRuleProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }

    private void scheduleAlertProjectMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getAlertProjectMigration();
        if (config == null || !config.enabled()) {
            log.info("Alert project migration job is disabled");
            return;
        }
        scheduleRepeatingJob(AlertProjectMigrationJob.class,
                config.interval().toJavaDuration(),
                config.startupDelay().toJavaDuration());
    }
}
