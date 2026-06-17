package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@DisallowConcurrentExecution
@On(value = "0 0/5 * * * ?", timeZone = "UTC") // every 5 minutes
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class McpOAuthScrubJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("mcp_oauth_scrub:lock");

    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull LockService lockService;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    /**
     * Revoked tokens within the rotation grace window must remain queryable, so a duplicate refresh
     * request (RFC 6749 §6 token rotation grace) returns invalid_grant instead of triggering reuse detection.
     */
    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("MCP OAuth scrub interrupted before start");
            return;
        }

        // We don't run a full Quartz cluster, so each replica fires this schedule independently.
        // A best-effort Redis lock held until expiry ensures a single replica scrubs per cycle.
        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("MCP OAuth scrub was interrupted before processing, skipping");
                        return Mono.empty();
                    }
                    return Mono.fromRunnable(this::scrub);
                }),
                Mono.fromRunnable(
                        () -> log.debug("Could not acquire lock for MCP OAuth scrub, another instance is running")),
                opikConfig.getMcpOAuth().getScrubLockTimeout(),
                opikConfig.getMcpOAuth().getScrubLockWaitTime(),
                true)
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("MCP OAuth scrub failed", error));
        currentExecution.set(subscription);
    }

    @Override
    public void interrupt() {
        log.info("Interrupting MCP OAuth scrub job");
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("MCP OAuth scrub job interrupted successfully");
        }
    }

    private void scrub() {
        Instant now = Instant.now();
        Instant tokenThreshold = now.minus(opikConfig.getMcpOAuth().getRefreshRotationGrace());

        int codes = template.inTransaction(WRITE,
                handle -> handle.attach(McpOAuthCodeDAO.class).deleteExpired(now));
        int tokens = template.inTransaction(WRITE,
                handle -> handle.attach(McpOAuthTokenDAO.class).deleteExpiredAndRevoked(tokenThreshold));
        log.info("MCP OAuth scrub: removed '{}' expired codes, '{}' expired/revoked tokens", codes, tokens);
    }
}
