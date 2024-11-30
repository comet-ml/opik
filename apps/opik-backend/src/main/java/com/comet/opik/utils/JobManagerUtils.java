package com.comet.opik.utils;

import io.dropwizard.jobs.JobManager;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JobManagerUtils {

    @Getter
    private static JobManager jobManager;

    public static synchronized void setJobManager(@NonNull JobManager jobManager) {

        if (JobManagerUtils.jobManager != null) {
            throw new IllegalStateException("JobManager already set");
        }

        JobManagerUtils.jobManager = jobManager;
    }

    public static synchronized void clearJobManager() {
        jobManager = null;
    }
}
