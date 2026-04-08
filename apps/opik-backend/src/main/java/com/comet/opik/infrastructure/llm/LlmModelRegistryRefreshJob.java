package com.comet.opik.infrastructure.llm;

import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

// No distributed lock needed: each instance independently fetches the same immutable YAML
// from the CDN. The result is identical across all pods, and the CDN handles the read load.
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class LlmModelRegistryRefreshJob extends Job {

    private final LlmModelRegistryService registryService;

    @Inject
    public LlmModelRegistryRefreshJob(LlmModelRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!registryService.isRemoteConfigured()) {
            return;
        }

        log.debug("Running LLM model registry refresh");
        registryService.reload();
    }
}
