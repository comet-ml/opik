package com.comet.opik.utils;

import io.dropwizard.jobs.JobManager;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.quartz.SchedulerException;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@UtilityClass
public class JobManagerUtils {

    @Getter
    private static volatile JobManager jobManager;

    public static synchronized void setJobManager(@NonNull JobManager jobManager) {
        if (JobManagerUtils.jobManager != null) {
            if (JobManagerUtils.jobManager == jobManager) {
                // Same instance, no need to set again
                log.debug("JobManager already set with the same instance, skipping");
                return;
            }
            
            // Properly shutdown the existing JobManager before replacing it
            log.info("Existing JobManager detected during setJobManager - this may indicate test cleanup issues");
            logRunningJobs();
            shutdown();

            JobManagerUtils.jobManager = null;
        }

        log.debug("Setting JobManager instance");
        JobManagerUtils.jobManager = jobManager;
    }

    public static synchronized void clearJobManager() {
        if (jobManager != null) {
            log.debug("Shutting down and clearing JobManager instance");
            logRunningJobs();
            shutdown();
        }
        jobManager = null;
    }
    
    private static void logRunningJobs() {
        try {
            var scheduler = jobManager.getScheduler();
            var runningJobs = scheduler.getCurrentlyExecutingJobs();
            if (!runningJobs.isEmpty()) {
                log.warn("Found {} currently executing jobs during shutdown", runningJobs.size());
                runningJobs.forEach(job -> log.warn("Running job: {}", job.getJobDetail().getKey()));
            }
        } catch (SchedulerException e) {
            log.warn("Error checking running jobs", e);
        }
    }

    private static void shutdown() {
        try {
            var scheduler = jobManager.getScheduler();
            
            // First, try graceful shutdown with timeout
            log.info("Attempting graceful scheduler shutdown...");
            scheduler.shutdown(false); // Don't wait for jobs to complete
            
            // Wait up to 10 seconds for graceful shutdown
            var shutdownStart = Instant.now();
            var maxWaitTime = Duration.ofSeconds(10);
            
            while (!scheduler.isShutdown() && Duration.between(shutdownStart, Instant.now()).compareTo(maxWaitTime) < 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            if (!scheduler.isShutdown()) {
                log.warn("Scheduler did not shut down gracefully within {} seconds, forcing shutdown", maxWaitTime.toSeconds());
                
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
}
