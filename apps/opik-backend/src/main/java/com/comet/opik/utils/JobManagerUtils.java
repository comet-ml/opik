package com.comet.opik.utils;

import io.dropwizard.jobs.JobManager;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;

@Getter
@Singleton
public class JobManagerUtils {

    private JobManager jobManager;

    public synchronized void setJobManager(@NonNull JobManager jobManager) {

        if (this.jobManager != null) {
            throw new IllegalStateException("JobManager already set");
        }

        this.jobManager = jobManager;
    }

    public synchronized void clearJobManager() {
        this.jobManager = null;
    }
}
