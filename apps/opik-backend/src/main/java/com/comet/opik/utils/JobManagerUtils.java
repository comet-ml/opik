package com.comet.opik.utils;

import io.dropwizard.jobs.JobManager;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobManagerUtils {

    @Getter
    private static JobManager jobManager;

    public static void setJobManager(@NonNull JobManager jobManager) {

        if (JobManagerUtils.jobManager != null) {
            log.info("JobManager already set");
            return;
        }

        JobManagerUtils.jobManager = jobManager;
    }

}
