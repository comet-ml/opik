package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.JobTimeoutConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
public class TraceThreadsClosingJob extends Job implements InterruptableJob {

    private final TraceThreadService traceThreadService;
    private final LockService lockService;
    private final TraceThreadConfig traceThreadConfig;
    private final RedissonReactiveClient redisClient;
    private final JobTimeoutConfig jobTimeoutConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public TraceThreadsClosingJob(@NonNull TraceThreadService traceThreadService,
            @NonNull LockService lockService,
            @NonNull @Config TraceThreadConfig traceThreadConfig,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("jobTimeout") JobTimeoutConfig jobTimeoutConfig) {
        this.traceThreadService = traceThreadService;
        this.lockService = lockService;
        this.traceThreadConfig = traceThreadConfig;
        this.redisClient = redisClient;
        this.jobTimeoutConfig = jobTimeoutConfig;
    }

    @Override
    public void doJob(JobExecutionContext jobExecutionContext) {
        // Check for interruption before starting
        log.info("Do nothing");
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("TraceThreadsClosingJob interruption completed");
    }
}
