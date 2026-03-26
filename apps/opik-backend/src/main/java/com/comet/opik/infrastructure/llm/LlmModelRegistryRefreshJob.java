package com.comet.opik.infrastructure.llm;

import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

@Slf4j
@Singleton
@DisallowConcurrentExecution
@On(value = "0 0/5 * * * ?", timeZone = "UTC")
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
