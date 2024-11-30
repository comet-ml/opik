package com.comet.opik.infrastructure.bi;

import com.comet.opik.utils.JobManagerUtils;
import com.google.inject.Injector;
import io.dropwizard.jobs.GuiceJobManager;
import io.dropwizard.jobs.JobConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.UsageReportConfig.ServerStatsConfig;

@Slf4j
@RequiredArgsConstructor
public class OpikGuiceyLifecycleEventListener implements GuiceyLifecycleListener {

    // This event cannot depend on authentication
    private final AtomicReference<Injector> injector = new AtomicReference<>();

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {

        if (event.getType() == GuiceyLifecycle.ApplicationRun && event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());

            log.info("Installing jobs...");
            JobConfiguration configuration = injectorEvent.getConfiguration();
            var jobManager = new GuiceJobManager(configuration, injector.get());
            injectorEvent.getEnvironment().lifecycle().manage(jobManager);
            JobManagerUtils.setJobManager(jobManager);
            log.info("Jobs installed.");
        }

        if (event.getType() == GuiceyLifecycle.ApplicationStarted) {
            var installationReportService = injector.get().getInstance(InstallationReportService.class);

            installationReportService.reportInstallation();

            setupDailyJob();
        }
    }

    private void setupDailyJob() {

        var serverStatsConfig = injector.get().getInstance(ServerStatsConfig.class);

        if (!serverStatsConfig.enabled()) {
            log.info("Daily usage report disabled, unregistering job.");

            var scheduler = getJobManager();

            var jobKey = new JobKey(DailyUsageReport.class.getName());

            try {
                if (scheduler.checkExists(jobKey)) {
                    scheduler.deleteJob(jobKey);
                    log.info("Job '{}' unregistered.", jobKey);
                } else {
                    log.info("Job '{}' not found.", jobKey);
                }
            } catch (SchedulerException e) {
                log.error("Failed to unregister job '{}'", jobKey, e);
            }
        } else {
            JobKey key = JobKey.jobKey(DailyUsageReport.class.getName());

            try {
                var scheduler = getJobManager();

                if (scheduler.isShutdown()) {
                    scheduler.start();
                }

                Trigger trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();

                scheduler.scheduleJob(trigger);
                log.info("Daily usage report enabled, running job.");
            } catch (SchedulerException e) {
                log.error("Failed to schedule job '{}'", key, e);
            }

        }
    }

    private Scheduler getJobManager() {
        return JobManagerUtils.getJobManager().getScheduler();
    }

}
