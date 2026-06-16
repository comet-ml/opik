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
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

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
        lockService.bestEffortLock(
                JOB_LOCK,
                Mono.fromRunnable(this::scrub),
                Mono.fromRunnable(
                        () -> log.debug("Could not acquire lock for MCP OAuth scrub, another instance is running")),
                Duration.ofMinutes(1),
                Duration.ZERO,
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("MCP OAuth scrub failed", error));
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("MCP OAuth scrub job successfully called interruption");
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
