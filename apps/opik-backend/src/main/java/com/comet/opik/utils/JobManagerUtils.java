package com.comet.opik.utils;

import io.dropwizard.jobs.JobManager;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

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

            clearJobManager();
        }

        log.debug("Setting JobManager instance");
        JobManagerUtils.jobManager = jobManager;
    }

    public static synchronized void clearJobManager() {
        log.debug("Clearing JobManager instance");
        jobManager = null;
    }
}
